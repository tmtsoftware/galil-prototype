package csw.proto.galil.hcd.hmi

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.util.Timeout
import csw.command.client.messages.SupervisorContainerCommonMessages
import csw.command.client.messages.ComponentMessage
import csw.command.client.models.framework.ComponentInfo
import csw.location.api.extensions.URIExtension._
import csw.location.api.models.ComponentId
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.scaladsl.LocationService
import csw.logging.api.scaladsl.Logger
import csw.logging.client.commons.LogAdminUtil
import csw.logging.models.Level
import csw.params.commands.{CommandName, Setup}
import csw.params.core.models.Id
import csw.prefix.models.Prefix
import csw.proto.galil.hcd._
import csw.proto.galil.hcd.hmi.HmiJsonProtocol._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Embedded HTTP/WebSocket server for the GalilMotion HCD test console.
 *
 * Provides:
 *   GET  /              -> React SPA (index.html from resources/web/)
 *   WS   /ws/state      -> Streaming JSON state updates
 *   POST /api/command   -> Submit CSW commands via JSON
 *   GET  /api/status    -> One-shot full state snapshot
 *   POST /api/shutdown  -> Initiate lifecycle Shutdown of the HCD component
 *
 * The server subscribes to InternalStateActor state changes and broadcasts
 * them to all connected WebSocket clients as JSON frames.
 *
 * Lifecycle: created during GalilHcdHandlers.initialize(), stopped on shutdown.
 */
class HmiServer(
  internalStateActor: ActorRef[InternalStateActor.Command],
  commandHandlerActor: ActorRef[CommandHandlerActor.Command],
  // Direct handle for HMI-internal engineering operations (engJog/engStop)
  // that bypass CommandHandlerActor and the embedded-program path entirely.
  // These low-level operations issue JG/BG/ST directly on the command
  // connection; the engineer takes responsibility for soft-limit and
  // tuning correctness. Reserved for lab/test use; not exposed via CSW.
  controllerCommandActor: ActorRef[GalilCommandMessage],
  hcdPrefix: Prefix,
  log: Logger,
  locationService: LocationService,
  componentInfo: ComponentInfo,
  port: Int = 9090,
  // faultReset is dispatched directly to GalilHcd; it does not
  // flow through CommandHandlerActor because it drives HCD-level lifecycle
  // state and re-uses the shared init sequence.  GalilHcd provides this
  // callback at construction time.  The callback is responsible for kicking
  // off the recovery off-thread (it is not a blocking call).
  onFaultReset: (Setup, Id) => Unit,
  // Supplier for the latest per-JVM CPU sample, folded into each stateUpdate frame
  // (REQ-2-APS-0621 readout). Deferred (evaluated at push time) so the HCD can pass it
  // before the CpuLoadMonitor field is assigned. Defaults to "no sample".
  cpuLoad: () => Option[CpuLoadMonitor.Sample] = () => None,
  // Per-scan axis position history (ADR-002).  Written by ControllerStatusActor at the
  // QR scan boundary; this server is a read-only consumer.
  positionHistory: PositionHistoryBuffer
)(implicit system: ActorSystem[?]) {

  implicit val ec: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout(5.seconds)

  // ── WebSocket broadcast infrastructure ──────────────────────────────
  //
  // Source.actorRef creates a materialized ActorRef that we push JSON strings to.
  // BroadcastHub fans out to all connected WS clients with backpressure.
  private val (wsPublisher, wsSource) = Source
    .actorRef[String](
      completionMatcher = PartialFunction.empty,
      failureMatcher = PartialFunction.empty,
      bufferSize = 64,
      overflowStrategy = OverflowStrategy.dropHead
    )
    .toMat(BroadcastHub.sink(bufferSize = 64))(Keep.both)
    .run()

  // Timer-driven WebSocket state push at a fixed rate (4Hz).
  //
  // Decouple WebSocket push rate from IS update rate. A 4Hz scheduler
  // tick asks IS for the current state snapshot and pushes it. The system dispatcher
  // sees at most 4 state publishes/second regardless of QR poll rate or motion state.
  private val wsUpdateInterval: FiniteDuration = 250.millis  // 4Hz

  // Server-global cursor into the position history (ADR-002).  Seeded at the buffer's
  // current head so the first tick after startup does not dump the whole retained
  // window into the socket; clients that connect later backfill via GET /api/history.
  //
  // The cursor is server-global rather than per-client because the WebSocket is a single
  // BroadcastHub -- every client sees the same frames.  Per-client cursors would mean
  // restructuring the hub for no benefit at the scale of one or two engineering clients.
  @volatile private var lastPushedSampleSeq: Long = positionHistory.nextSequence

  private val wsCancellable = system.scheduler.scheduleWithFixedDelay(
    initialDelay = wsUpdateInterval,
    delay = wsUpdateInterval
  )(() => {
    import AskPattern._
    internalStateActor
      .ask[HcdState](ref => InternalStateActor.GetHcdState(ref))(timeout, system.scheduler)
      .foreach { state =>
        wsPublisher ! stateToJson(state, hcdPrefix, cpuLoad())
      }

    // Position samples ride the SAME 4Hz tick, but each frame carries every sample
    // recorded since the previous tick -- typically one at the 1Hz standby scan rate and
    // two or three at the 10Hz action rate.  This is what delivers full scan-rate
    // fidelity without raising the WebSocket frame rate: raising the push rate to 10Hz
    // would inflate the full stateUpdate snapshot too, which is a much larger payload
    // that nothing else on the page needs faster (ADR-002 section 5).
    val samples = positionHistory.snapshot(lastPushedSampleSeq)
    if (samples.size > 0) {
      lastPushedSampleSeq = samples.nextSeq
      wsPublisher ! positionSamplesToJson(samples, positionHistory.capacity)
    }
  })(ec)

  // Register HmiLogAppender broadcast; the appender is instantiated by the CSW
  // framework before HmiServer exists, so wiring is done here at start time.
  // All HCD log messages (including [GALIL:prefix] controller console lines) will
  // be pushed to connected WebSocket clients as "logLine" JSON messages.
  //
  // Log lines are ALSO retained in a bounded ring so a client that connects (or
  // reconnects, or is hard-reloaded) gets recent history rather than starting blank.
  //
  // Why this matters more than it looks: the HMI's reconnect path deliberately calls
  // window.location.reload() to clear stale React state after an HCD Restart, and the
  // page is hand-edited often enough that hard reloads are routine.  Without retention,
  // every one of those discards the entire log -- including the init sequence emitted
  // during GalilHcd.initialize(), which happens before any browser can be connected and
  // was therefore never visible to the HMI at all.  That is usually the most interesting
  // part of a restart.
  //
  // The frames are stored already-encoded: they are broadcast verbatim, so re-encoding
  // on replay could only introduce a difference between what a live client saw and what
  // a reconnecting one sees.
  //
  // Sized to what the HMI panel can usefully display rather than to what the JVM could
  // hold.  This buffer exists to survive a reload, not to be a log archive -- the CSW
  // log files under TMT_LOG_HOME are the durable record, and anything this ring drops is
  // still there.  Keep it equal to the client's LOG_RETAIN in resources/web/index.html:
  // retaining more than the panel keeps just means shipping frames that are discarded
  // on arrival.
  private val LogRingCapacity = 1000

  private val logRing = new Array[String](LogRingCapacity)
  private var logRingWrite = 0
  private var logRingSize  = 0
  private val logRingLock  = new AnyRef

  private def broadcastLogLine(json: String): Unit = {
    logRingLock.synchronized {
      logRing(logRingWrite) = json
      logRingWrite = (logRingWrite + 1) % LogRingCapacity
      if (logRingSize < LogRingCapacity) logRingSize += 1
    }
    wsPublisher ! json
  }

  /** Retained log frames, oldest first.  Copied under the lock so a caller can never
   *  observe the ring mid-rotation. */
  private def logRingSnapshot(): IndexedSeq[String] = logRingLock.synchronized {
    val out   = new Array[String](logRingSize)
    val start = (logRingWrite - logRingSize + LogRingCapacity) % LogRingCapacity
    var i = 0
    while (i < logRingSize) {
      out(i) = logRing((start + i) % LogRingCapacity)
      i += 1
    }
    out.toIndexedSeq
  }

  // ── HTTP Routes ─────────────────────────────────────────────────────

  private val routes: Route = concat(
    // WebSocket endpoint for streaming state updates
    path("ws" / "state") {
      handleWebSocketMessages(wsFlow)
    },

    // REST: submit a command
    path("api" / "command") {
      post {
        entity(as[String]) { body =>
          val response = handleCommandRequest(body)
          complete(HttpEntity(ContentTypes.`application/json`, response))
        }
      }
    },

    // REST: get/set HCD component log level via the same mechanism as CSW Admin API
    // (LogAdminUtil is what SupervisorBehavior uses for SetComponentLogLevel messages)
    path("api" / "loglevel") {
      get {
        val metadata = LogAdminUtil.getLogMetadata(hcdPrefix)
        val level    = metadata.componentLevel.name
        complete(HttpEntity(ContentTypes.`application/json`, s"""{"logLevel":"$level"}"""))
      } ~
      post {
        entity(as[String]) { body =>
          try {
            val json      = Json.parse(body)
            val levelName = (json \ "logLevel").as[String]
            val level     = Level(levelName)
            LogAdminUtil.setComponentLogLevel(hcdPrefix, level)
            HmiLogAppender.minSeverity = level.name  // keep HMI display in sync
            log.info(s"HCD component log level changed to ${level.name} via HMI")
            complete(HttpEntity(ContentTypes.`application/json`, s"""{"logLevel":"${level.name}"}"""))
          } catch {
            case ex: Exception =>
              complete(StatusCodes.BadRequest, s"""{"error":"${ex.getMessage}"}""")
          }
        }
      }
    },

    // REST: one-shot status snapshot
    path("api" / "status") {
      get {
        import AskPattern._
        val futureState: Future[HcdState] =
          internalStateActor.ask[HcdState](ref => InternalStateActor.GetHcdState(ref))(timeout, system.scheduler)
        onComplete(futureState) {
          case Success(state) =>
            complete(HttpEntity(ContentTypes.`application/json`, stateToJson(state, hcdPrefix)))
          case Failure(ex) =>
            complete(StatusCodes.InternalServerError, s"Failed to get state: ${ex.getMessage}")
        }
      }
    },

    // REST: position history backfill (ADR-002).
    //
    // The plot panel calls this once on open to render immediately with whatever the
    // HCD has retained, then follows the positionSamples WebSocket frames.  It is also
    // the recovery path after a `gap` frame or a missed reconnect.
    //
    // `since` is a sample sequence number, not a timestamp: sequence numbers are exact
    // and monotonic, whereas a time-based cursor would need tie-breaking whenever two
    // scans landed in the same millisecond.  Omit it (or pass 0) for the whole window.
    path("api" / "history") {
      get {
        parameterMap { params =>
          val since = params.get("since").flatMap(s => scala.util.Try(s.toLong).toOption).getOrElse(0L)
          val snap  = positionHistory.snapshot(since)
          complete(HttpEntity(ContentTypes.`application/json`,
            positionSamplesToJson(snap, positionHistory.capacity, msgType = "positionHistory")))
        }
      }
    },

    // REST: position history as CSV, for STB hardware sessions.
    //
    // Useful precisely because this data does not survive anywhere else: the archived
    // path (assembly SystemEvent -> DMS) is throttled to 1Hz, so a downloaded window is
    // the only dense record of a move that will exist after the buffer wraps.
    path("api" / "history.csv") {
      get {
        parameterMap { params =>
          val since = params.get("since").flatMap(s => scala.util.Try(s.toLong).toOption).getOrElse(0L)
          val snap  = positionHistory.snapshot(since)
          val name  = s"galil-position-${hcdPrefix.toString.replace('.', '-')}.csv"
          respondWithHeader(
            headers.`Content-Disposition`(headers.ContentDispositionTypes.attachment, Map("filename" -> name))
          ) {
            complete(HttpEntity(ContentTypes.`text/csv(UTF-8)`, positionHistoryCsv(snap)))
          }
        }
      }
    },

    // REST: initiate lifecycle Shutdown of the HCD component.
    // Resolves this component's own supervisor via the location service
    // and sends SupervisorContainerCommonMessages.Shutdown. The supervisor
    // stops the TLA, which triggers PostStop → lifecycleHandlers.onShutdown(),
    // and (since the HCD runs as a standalone process) terminates the JVM.
    //
    // Returns immediately after the message is sent; the actual teardown
    // happens asynchronously and the WebSocket will drop within a few seconds.
    // Available even when the HCD is in a Faulted state; Shutdown is an
    // admin operation, not a regular command, and is not gated.
    path("api" / "shutdown") {
      post {
        onComplete(sendSupervisorMessage("Shutdown", SupervisorContainerCommonMessages.Shutdown)) {
          case Success(msg) =>
            complete(HttpEntity(ContentTypes.`application/json`, s"""{"status":"$msg"}"""))
          case Failure(ex) =>
            log.error(s"HMI shutdown request failed: ${ex.getMessage}")
            complete(StatusCodes.InternalServerError,
              HttpEntity(ContentTypes.`application/json`, s"""{"error":"${ex.getMessage}"}"""))
        }
      }
    },

    // REST: initiate lifecycle Restart of the HCD component.
    // Resolves this component's own supervisor and sends
    // SupervisorContainerCommonMessages.Restart. The supervisor unregisters
    // from the Location Service, stops the TLA (PostStop → onShutdown), then
    // re-creates a fresh TLA and runs initialize() again.  Net effect: HCD
    // internal state, controller TCP connections, and embedded program load
    // are all reset, but the JVM stays alive; the HMI WebSocket client will
    // see a brief disconnect (auto-reconnects after 2s) and then resume.
    //
    // Available even when the HCD is in a Faulted state; Restart is an admin
    // operation and a strictly bigger hammer than faultReset for HCD-internal
    // issues.  Like Shutdown, it is not gated by the Faulted guard.
    path("api" / "restart") {
      post {
        onComplete(sendSupervisorMessage("Restart", SupervisorContainerCommonMessages.Restart)) {
          case Success(msg) =>
            complete(HttpEntity(ContentTypes.`application/json`, s"""{"status":"$msg"}"""))
          case Failure(ex) =>
            log.error(s"HMI restart request failed: ${ex.getMessage}")
            complete(StatusCodes.InternalServerError,
              HttpEntity(ContentTypes.`application/json`, s"""{"error":"${ex.getMessage}"}"""))
        }
      }
    },

    // Static files: serve the React SPA from resources/web/.
    //
    // no-cache/no-store on the page itself.  The HMI is a single hand-edited HTML file
    // that changes on essentially every build, and a stale or partially-cached copy
    // presents as a JavaScript syntax error at a line number that matches no version on
    // disk -- which looks exactly like a code fault and survives an HCD restart, since
    // the stale bytes live in the browser.  Revalidating a ~100 KB local page costs
    // nothing next to that diagnosis.
    //
    // The vendored libraries below are deliberately NOT covered: they are versioned,
    // change only when we re-vendor, and are the bulk of the transfer.
    pathEndOrSingleSlash {
      respondWithHeader(headers.`Cache-Control`(
        headers.CacheDirectives.`no-cache`, headers.CacheDirectives.`no-store`
      )) {
        getFromResource("web/index.html", ContentTypes.`text/html(UTF-8)`)
      }
    },
    // Serve other static assets (vendored JS, etc.)
    getFromResourceDirectory("web")
  )

  // ── WebSocket flow ──────────────────────────────────────────────────
  //
  // Incoming messages from the client are ignored (commands go via REST).
  // Outgoing: broadcast state updates as TextMessage frames.
  private def wsFlow: Flow[Message, Message, Any] = {
    // Replay the retained log frames to this client before joining the live broadcast.
    //
    // Source.fromIterator takes a FACTORY and calls it once per materialization, which is
    // what makes the snapshot per-connection.  This matters because `routes` is a val, so
    // wsFlow itself is evaluated exactly once at construction -- a Source built eagerly
    // here would hand every future client the same (empty, server-startup) snapshot.
    //
    // concat rather than merge: history must arrive before live frames, in order.  The
    // cost is that frames published while the finite backfill drains are not seen by this
    // client; draining a few thousand pre-encoded strings takes microseconds, and the
    // alternative (interleaving) would reorder the log, which is worse.
    val backfill = Source
      .fromIterator(() => logRingSnapshot().iterator)
      .map(json => TextMessage.Strict(json): Message)

    Flow.fromSinkAndSource(
      Sink.ignore,
      backfill.concat(wsSource.map(json => TextMessage.Strict(json): Message))
    )
  }

  // ── Log level helpers ───────────────────────────────────────────────

  // ── Command handling ────────────────────────────────────────────────

  /**
   * Resolve this component's own supervisor via the location service and
   * send the given lifecycle message (Shutdown or Restart).  Both messages
   * share the same resolve path; the only difference is which message is
   * delivered.
   *
   * The parameter type is `ComponentMessage` rather than the more specific
   * `SupervisorContainerCommonMessages` because the latter is an `object`
   * (containing the case objects), not a sealed-trait type.  `ComponentMessage`
   * is the common parent the supervisor's `ActorRef` accepts; both `Shutdown`
   * and `Restart` extend it, as do the other administrative messages we may
   * later add (e.g. `GoOffline` / `GoOnline` if exposed in the HMI).
   *
   * @param action  short human label used for logging (e.g. "Shutdown", "Restart")
   * @param message the actual lifecycle message to send
   * @return Future of a status string on success, or fails with an exception
   *         if the supervisor location can't be resolved.
   */
  private def sendSupervisorMessage(
    action: String,
    message: ComponentMessage
  ): Future[String] = {
    log.warn(s"HMI: $action requested for ${componentInfo.prefix}")
    val connection = PekkoConnection(ComponentId(componentInfo.prefix, componentInfo.componentType))
    locationService.resolve(connection, 5.seconds).map {
      case Some(pekkoLocation) =>
        val supervisor: ActorRef[ComponentMessage] =
          pekkoLocation.uri.toActorRef.unsafeUpcast[ComponentMessage]
        supervisor ! message
        log.info(s"HMI: $action message sent to supervisor for ${componentInfo.prefix}")
        "initiated"
      case None =>
        val msg = s"Could not resolve supervisor location for ${componentInfo.prefix}"
        log.error(s"HMI: $msg")
        throw new RuntimeException(msg)
    }
  }

  /**
   * Parse a JSON command request, build a CSW Setup, and submit it
   * to the CommandHandlerActor.
   *
   * Returns JSON response string with runId and status.
   */
  private def handleCommandRequest(body: String): String = {
    try {
      val request = parseCommandRequest(body)
      val runId = Id()

      // Gate: reject all commands when the HCD is not in Ready state.
      //   Uninitialized; startup is still in progress.  No exemptions:
      //                  faultReset has nothing to reset, setSoftLimits
      //                  could race the initial axis-config writes, and
      //                  no other command should reach the controller
      //                  before init completes.
      //   Faulted     ; operator must clear the fault first; faultReset
      //                  is permitted, and setSoftLimits is permitted
      //                  because it's an HMI-internal flag flip useful
      //                  for preparing limit-switch tests before recovery.
      // Ready commands fall through to normal handling.
      // Shared with the CSW path via CommandGate; the HMI exempts both
      // faultReset and the HMI-only setSoftLimits action during Faulted.
      val hcdState = queryHcdStateForHmi()
      CommandGate.checkHcdState(hcdState, request.commandName, Set("faultReset", "setSoftLimits")) match
        case Some(reason) =>
          log.warn(s"HMI command '${request.commandName}' rejected: $reason")
          return commandResponseJson(runId.id, "Error", reason)
        case None => // Ready, or Faulted-with-faultReset/setSoftLimits — proceed

      // HMI-internal action: per-axis soft-limit bypass toggle.  Not exposed to
      // assemblies (no CSW Setup, no CommandHandlerActor); fires UpdateAxisState
      // directly to the InternalStateActor.  The HMI sends:
      //   { commandName: "setSoftLimits", params: { axis: "A", enabled: true } }
      if request.commandName == "setSoftLimits" then
        return handleSetSoftLimits(request.params, runId)

      // HMI-internal action: low-level engineering jog.  Issues SH;JG;BG directly
      // on the command connection, bypassing CommandHandlerActor and any embedded
      // program.  Reserved for lab/test use (limit-switch testing, motor checkout
      // before homing).  Engineer takes responsibility for soft-limit and tuning
      // correctness; the only HCD-side protection is the gating predicate (axis
      // must be Idle, Lost, or already in an engineering jog).  See engJog handler.
      if request.commandName == "engJog" then
        return handleEngJog(request.params, runId, hcdState)
      if request.commandName == "engStop" then
        return handleEngStop(request.params, runId, hcdState)

      // Axis-state-machine + soft-limit gate for long-running axis commands.
      // The CSW path runs both checks in validateCommand and rejects before
      // submitting; the HMI bypasses that path, so we replicate both here to
      // give the operator a synchronous rejection (amber REJ bar) rather than
      // a silent CRM-only Error from the CHA backstop.  Both checks share a
      // single InternalState query.  Runs AFTER the engJog/engStop/setSoftLimits
      // short-circuits above so those HMI-only engineering actions are never
      // subject to the CSW axis-state machine.
      axisCommandRejection(request.commandName, request.params) match
        case Some(reason) =>
          log.warn(s"HMI command '${request.commandName}' rejected: $reason")
          return commandResponseJson(runId.id, "Error", reason)
        case None => // accepted, fall through

      // Build CSW Setup from JSON params using ICD key objects
      val setup = buildSetup(request.commandName, request.params, runId)

      // faultReset is dispatched directly to GalilHcd (not CHA) because it
      // drives HCD lifecycle state and re-uses the shared init sequence.
      // The callback runs the recovery off-thread; we return Started here
      // and the eventual Completed/Error arrives via the CRM (which the
      // HMI doesn't currently observe; state changes are visible through
      // the WebSocket stream as the recovery progresses).
      if (request.commandName == "faultReset") {
        onFaultReset(setup, runId)
        return commandResponseJson(runId.id, "Started")
      }

      // Submit to CommandHandlerActor (fire-and-forget from HMI perspective;
      // the WebSocket stream will show state changes as the command progresses)
      commandHandlerActor ! CommandHandlerActor.HandleCommand(setup, runId, None)

      // Return Started for long-running, or Completed for immediate
      val status = if (CommandHandlerActor.isImmediate(request.commandName)) "Completed" else "Started"
      commandResponseJson(runId.id, status)
    } catch {
      case ex: Exception =>
        log.error(s"HMI command error: ${ex.getMessage}")
        commandResponseJson("", "Error", ex.getMessage)
    }
  }

  /**
   * HMI-internal handler for the setSoftLimits action.  Updates the per-axis
   * softLimitsEnabled flag directly in InternalState; not a CSW command.  No
   * controller interaction; the flag is consulted at command-validation time only.
   */
  private def handleSetSoftLimits(params: Map[String, JsValue], runId: Id): String = {
    val axisChar = params.get("axis") match
      case Some(JsString(s)) if s.length == 1 => s.head.toUpper
      case _ =>
        return commandResponseJson(runId.id, "Error", "setSoftLimits: missing or invalid 'axis' parameter")
    val enabled = params.get("enabled") match
      case Some(JsBoolean(b)) => b
      case _ =>
        return commandResponseJson(runId.id, "Error", "setSoftLimits: missing or invalid 'enabled' parameter")
    try
      val axis = Axis.fromChar(axisChar)
      internalStateActor ! InternalStateActor.UpdateAxisState(axis,
        Map("softLimitsEnabled" -> enabled),
        system.ignoreRef)
      log.info(s"HMI: softLimitsEnabled=$enabled on axis $axisChar")
      commandResponseJson(runId.id, "Completed")
    catch
      case ex: IllegalArgumentException =>
        commandResponseJson(runId.id, "Error", s"setSoftLimits: ${ex.getMessage}")
  }

  /**
   * HMI-internal handler: low-level engineering jog.
   *
   * Issues JG/BG (with conditional SH if motor off) directly on the command
   * connection via controllerCommandActor.SendCommand; bypassing
   * CommandHandlerActor and the embedded-program path entirely.  No thread is
   * allocated, no CommandWatcher is spawned; the engineer drives the motion
   * with their own start/stop decisions.
   *
   * Frontend sends:
   *   { commandName: "engJog", params: { axis: "A", speed: 30 } }
   * where speed is signed counts/s (positive = forward, negative = reverse).
   *
   * Gating predicate (re-checked here against the live HcdState snapshot so
   * a stale HMI cannot start a jog while an embedded program is running):
   *   - HCD state == Ready
   *   - Axis state ∈ {Idle, Lost}              ← first jog from a quiescent state
   *     OR (Axis state == Moving && activeThread == -1)  ← reentrant speed update
   *       (activeThread >= 0 means an embedded motion program is running, which
   *        we cannot interrupt without going through CommandHandlerActor.  The
   *        engineer must issue a CSW stopAxis first.)
   *
   * On success, axisState is set to Moving so the existing CSW command
   * machinery treats subsequent commands as interrupting motion (checkAndInterrupt
   * will send ST to stop the jog when a positionAxis/offsetAxis/stopAxis arrives).
   *
   * Note: ControllerStatusActor's spontaneous-motion detector watches for
   * moving=true while axisState ∈ {Idle, Lost}.  We deliberately update
   * axisState=Moving BEFORE sending the JG;BG so the detector cannot fire on
   * the first QR scan that sees our motion.  (If the JG itself fails we revert
   * axisState in the error path so the detector also stays correct.)
   */
  private def handleEngJog(params: Map[String, JsValue], runId: Id, hcdState: HcdState): String = {
    import scala.concurrent.Await
    import scala.concurrent.duration._

    // ─── Parse params ───
    val axisChar = params.get("axis") match
      case Some(JsString(s)) if s.length == 1 => s.head.toUpper
      case _ =>
        return commandResponseJson(runId.id, "Error", "engJog: missing or invalid 'axis' parameter")
    val speed = params.get("speed") match
      case Some(JsNumber(n)) => n.toLong
      case Some(JsString(s)) =>
        scala.util.Try(s.toLong).toOption match
          case Some(v) => v
          case None =>
            return commandResponseJson(runId.id, "Error", s"engJog: invalid 'speed' parameter '$s'")
      case _ =>
        return commandResponseJson(runId.id, "Error", "engJog: missing or invalid 'speed' parameter")

    val axis = try Axis.fromChar(axisChar) catch
      case ex: IllegalArgumentException =>
        return commandResponseJson(runId.id, "Error", s"engJog: ${ex.getMessage}")

    // ─── Gating ─── (HCD lifecycle already vetted by the caller's Ready check;
    // re-check here defensively in case future routing skips that path)
    if hcdState.state != HcdStateEnum.Ready then
      return commandResponseJson(runId.id, "Error",
        s"engJog: HCD not Ready (state=${hcdState.state})")

    val axState = hcdState.axes.get(axis) match
      case Some(s) => s
      case None =>
        return commandResponseJson(runId.id, "Error", s"engJog: axis $axisChar not configured")
    val cmdState = hcdState.cmdStates.getOrElse(axis, AxisCmdState())

    val gatingOk = axState.axisState match
      case AxisStateEnum.Idle | AxisStateEnum.Lost => true
      case AxisStateEnum.Moving if cmdState.activeThread == -1 => true  // reentrant (jog, no program)
      case _ => false

    if !gatingOk then
      val reason = axState.axisState match
        case AxisStateEnum.Moving =>  // activeThread >= 0
          s"axis $axisChar is executing an embedded program (thread ${cmdState.activeThread}) — issue stopAxis first"
        case other =>
          s"axis $axisChar is in $other state — engineering jog requires Idle, Lost, or active engineering jog"
      log.warn(s"HMI engJog rejected: $reason")
      return commandResponseJson(runId.id, "Error", reason)

    // ─── Build command string ───
    // SH only if motor currently off; on a reentrant speed update the motor is
    // already energised so SH would be a no-op.  BG while already moving is
    // harmless (empirically verified on hardware S61).
    val needsSH = axState.motorOff
    val cmdString =
      if needsSH then s"SH$axisChar;JG$axisChar=$speed;BG$axisChar"
      else            s"JG$axisChar=$speed;BG$axisChar"

    log.info(s"HMI engJog axis=$axisChar speed=$speed (axisState=${axState.axisState}, " +
      s"motorOff=${axState.motorOff}) → '$cmdString'")

    // ─── Update axisState FIRST so the spontaneous-motion detector in
    // InternalStateActor.handleUpdateAxisCmdState does not flag our own jog as
    // unexplained motion.  Ordering: this UpdateAxisState message is enqueued
    // to IS before we issue SendCommand to CCA; CS only sends its
    // UpdateAxisCmdState(moving=true) after the next QR poll (which itself is
    // sequenced after the JG;BG round-trip).  Pekko doesn't guarantee ordering
    // across different senders, but the time from IS receiving our message to
    // CS sending its update is at least one QR poll period (≥100ms), so the
    // IS mailbox drains the axisState=Moving update first in practice.  If the
    // controller rejects the JG;BG (e.g. axis hard-faulted between the gating
    // check and the send), we revert to the prior axisState so the world
    // stays consistent. ───
    val priorState = axState.axisState
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Moving),
      system.ignoreRef)

    // ─── Send via the command connection ───
    try
      val result = Await.result(
        AskPattern.Askable(controllerCommandActor).ask[GalilCommandMessage.SendCommandResult](
          ref => GalilCommandMessage.SendCommand(cmdString, ref)
        )(timeout, system.scheduler),
        timeout.duration
      )
      result.error match
        case Some(err) =>
          log.warn(s"HMI engJog axis=$axisChar: controller rejected '$cmdString' — $err")
          // Revert axisState; we never actually started moving
          internalStateActor ! InternalStateActor.UpdateAxisState(axis,
            Map("axisState" -> priorState),
            system.ignoreRef)
          commandResponseJson(runId.id, "Error", s"engJog: $err")
        case None =>
          commandResponseJson(runId.id, "Completed")
    catch
      case ex: Exception =>
        log.error(s"HMI engJog axis=$axisChar failed: ${ex.getMessage}")
        internalStateActor ! InternalStateActor.UpdateAxisState(axis,
          Map("axisState" -> priorState),
          system.ignoreRef)
        commandResponseJson(runId.id, "Error", s"engJog: ${ex.getMessage}")
  }

  /**
   * HMI-internal handler: stop a low-level engineering jog.
   *
   * Issues ST<axis> directly on the command connection.  Sets axisState back
   * to Idle or Lost (per stopCompletionState(homed)) so the HCD's worldview
   * matches the physical state after the stop completes.
   *
   * Frontend sends:
   *   { commandName: "engStop", params: { axis: "A" } }
   *
   * Permitted from any state; ST is fundamentally a safety command and the
   * engineer may want to stop a jog they did not start (e.g. an externally-
   * issued JG that triggered the spontaneous-motion detector).  The gating
   * predicate here is intentionally minimal.
   *
   * For symmetry with CSW stopAxis, motor remains energised after engStop , 
   * the engineer can issue another jog or any other command without an
   * intervening SH.
   */
  private def handleEngStop(params: Map[String, JsValue], runId: Id, hcdState: HcdState): String = {
    import scala.concurrent.Await
    import scala.concurrent.duration._

    val axisChar = params.get("axis") match
      case Some(JsString(s)) if s.length == 1 => s.head.toUpper
      case _ =>
        return commandResponseJson(runId.id, "Error", "engStop: missing or invalid 'axis' parameter")

    val axis = try Axis.fromChar(axisChar) catch
      case ex: IllegalArgumentException =>
        return commandResponseJson(runId.id, "Error", s"engStop: ${ex.getMessage}")

    if hcdState.state != HcdStateEnum.Ready then
      return commandResponseJson(runId.id, "Error",
        s"engStop: HCD not Ready (state=${hcdState.state})")

    val axState = hcdState.axes.get(axis) match
      case Some(s) => s
      case None =>
        return commandResponseJson(runId.id, "Error", s"engStop: axis $axisChar not configured")

    // Compute completion state using the same rules as CSW stopAxis (SDD Fig 4-2).
    // For engStop on a jog this will be Idle if homed, Lost if not.
    val completionState = axState.axisState.stopCompletionState(axState.homed)
    val cmdString = s"ST$axisChar"

    log.info(s"HMI engStop axis=$axisChar (axisState=${axState.axisState}, homed=${axState.homed}) → '$cmdString' " +
      s"→ completion state $completionState")

    // Poll-scan timestamp before the ST: the settle wait below requires a scan
    // NEWER than this before it will declare the stop complete, so a stale
    // observation snapshotted during the jog can never land after our state
    // transition (it is delivered, in order, before the newer scan we wait for).
    val preStopScan = hcdState.lastPollingTime

    try
      val result = Await.result(
        AskPattern.Askable(controllerCommandActor).ask[GalilCommandMessage.SendCommandResult](
          ref => GalilCommandMessage.SendCommand(cmdString, ref)
        )(timeout, system.scheduler),
        timeout.duration
      )
      result.error match
        case Some(err) =>
          log.warn(s"HMI engStop axis=$axisChar: controller rejected '$cmdString' — $err")
          commandResponseJson(runId.id, "Error", s"engStop: $err")
        case None =>
          if axState.axisState != AxisStateEnum.Moving then
            // No motion was declared, so no observation can contradict the
            // transition; keep the historical immediate behaviour.
            internalStateActor ! InternalStateActor.UpdateAxisState(axis,
              Map("axisState" -> completionState),
              system.ignoreRef)
            commandResponseJson(runId.id, "Completed")
          else
            // The axis was declared Moving (engineering jog).  Do NOT declare
            // the completion state until it is OBSERVED: transitioning to Idle
            // while a stale moving=true scan is still in flight trips the
            // spontaneous-motion detector (root cause of the S92 incident).
            //
            // Settle condition, provably stale-proof under per-sender FIFO
            // delivery from ControllerStatusActor to InternalStateActor:
            //   1. a scan NEWER than preStopScan has been applied, and
            //   2. that state shows moving == false, and
            //   3. a second query still shows moving == false.  The scan's
            //      time update and its per-axis moving update are separate
            //      messages; the confirming query is enqueued behind any
            //      still-undelivered moving update from the observed scan,
            //      so it cannot miss one.
            // On timeout the axis is left declared Moving: an axis that will
            // not stop must look like one, not like Idle.
            val deadline = System.nanoTime() + 3.seconds.toNanos
            var settled = false
            while !settled && System.nanoTime() < deadline do
              val q = queryHcdStateForHmi()
              val stillMoving = q.cmdStates.get(axis).exists(_.moving)
              if q.lastPollingTime.isAfter(preStopScan) && !stillMoving then
                val confirm = queryHcdStateForHmi()
                if !confirm.cmdStates.get(axis).exists(_.moving) then
                  settled = true
              if !settled then Thread.sleep(50)
            if settled then
              internalStateActor ! InternalStateActor.UpdateAxisState(axis,
                Map("axisState" -> completionState),
                system.ignoreRef)
              log.info(s"HMI engStop axis=$axisChar settled (observed moving=false " +
                s"on a post-stop scan) → $completionState")
              commandResponseJson(runId.id, "Completed")
            else
              log.warn(s"HMI engStop axis=$axisChar: axis still reports motion " +
                s"3s after ST — leaving axisState=Moving")
              commandResponseJson(runId.id, "Error",
                s"engStop: axis $axisChar still reports motion after ST; state left as Moving")
    catch
      case ex: Exception =>
        log.error(s"HMI engStop axis=$axisChar failed: ${ex.getMessage}")
        commandResponseJson(runId.id, "Error", s"engStop: ${ex.getMessage}")
  }

  /**
   * Combined axis-state-machine + soft-limit gate for the HMI command path,
   * mirroring GalilHcd.validateAxisStateAndLimits.  Applies only to the
   * long-running axis commands; immediate commands (configAxis etc.) and the
   * HMI-only engineering actions are not subject to it.  Shares a single
   * InternalState query across both checks.  Returns Some(reason) if the
   * command must be rejected, None otherwise.
   *
   * Fail-open on a missing/uninitialised axis or a query failure (returns None
   * → command falls through), matching the HMI's existing behaviour: the CHA
   * backstop and buildSetup still catch genuinely malformed commands.  (The
   * CSW path additionally rejects a genuinely-uninitialised axis; that edge is
   * unreachable once init has completed and all axes are present in IS.)
   *
   * Soft-limit target source: both positionAxis and offsetAxis carry their
   * value under the JSON key "target" (buildSetup maps offsetAxis's "target"
   * onto the ICD distanceKey); CommandGate.checkSoftLimit resolves the absolute
   * target from the command name (positionAxis = absolute, offsetAxis =
   * position + distance).
   */
  private def axisCommandRejection(commandName: String, params: Map[String, JsValue]): Option[String] =
    if !CommandHandlerActor.isLongRunning(commandName) then return None

    val axisChar = params.get("axis") match
      case Some(JsString(s)) if s.nonEmpty => s.head.toUpper
      case _ => return None  // missing axis → buildSetup will produce a clearer error

    val axis =
      try Axis.fromChar(axisChar)
      catch { case _: IllegalArgumentException => return None }

    queryAxisStateForHmi(axis).flatMap { axisState =>
      // 1. State machine first (SDD Figure 4-2).
      CommandGate.checkAxisState(axisState, commandName).orElse {
        // 2. Soft-limit envelope for positionAxis / offsetAxis.
        val rawTarget = params.get("target") match
          case Some(JsNumber(n)) => Some(n.toDouble)
          case Some(JsString(s)) => scala.util.Try(s.toDouble).toOption
          case _                 => None
        CommandGate.checkSoftLimit(axisState, commandName, axis.toString, rawTarget)
      }
    }

  /**
   * Synchronously query a single AxisState from InternalState for HMI gating.
   * Returns None on missing axis or query failure (fail-open, like the CSW path).
   */
  private def queryAxisStateForHmi(axis: Axis): Option[AxisState] =
    import scala.concurrent.Await
    try
      Await.result(
        AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
          ref => InternalStateActor.GetAxisState(axis, ref)
        )(timeout, system.scheduler),
        timeout.duration
      )
    catch
      case ex: Exception =>
        log.warn(s"HMI: axis state query failed for $axis: ${ex.getMessage}")
        None

  /**
   * Synchronously query HcdState from InternalStateActor for HMI command gating.
   * Fails closed; on query failure returns Faulted so commands are blocked.
   */
  private def queryHcdStateForHmi(): HcdState =
    import scala.concurrent.Await
    import scala.concurrent.duration._
    try
      Await.result(
        AskPattern.Askable(internalStateActor).ask[HcdState](
          ref => InternalStateActor.GetHcdState(ref)
        )(timeout, system.scheduler),
        timeout.duration
      )
    catch
      case ex: Exception =>
        log.warn(s"HMI: HCD state query failed: ${ex.getMessage} — treating as Faulted")
        HcdState(state = HcdStateEnum.Faulted,
                 controllerErrorMsg = "HCD state query failed")

  /**
   * Build a CSW Setup command from the HMI JSON parameters.
   *
   * Uses the ICD-generated key objects from GalilMotionKeys to ensure
   * parameter names and types exactly match what CommandHandlerActor expects.
   * Each command has its own key namespace in the ICD.
   */
  private def buildSetup(commandName: String, params: Map[String, JsValue], runId: Id): Setup = {
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    var setup = Setup(hcdPrefix, CommandName(commandName), None)

    // Helper: extract numeric value from JsNumber or JsString
    def numericParam(key: String): Option[Double] = params.get(key).map {
      case JsNumber(n) => n.toDouble
      case JsString(s) => s.toDouble
      case other => throw new IllegalArgumentException(s"Invalid $key: $other")
    }

    def stringParam(key: String): Option[String] = params.get(key).map {
      case JsString(s) => s
      case other => other.toString
    }

    def intParam(key: String): Option[Int] = params.get(key).map {
      case JsNumber(n) => n.toInt
      case JsString(s) => s.toInt
      case other => throw new IllegalArgumentException(s"Invalid $key: $other")
    }

    commandName match {

      case "homeAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(HomeAxisCommand.axisKey.set(a)))

      case "stopAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(StopAxisCommand.axisKey.set(a)))

      case "positionAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(PositionAxisCommand.axisKey.set(a)))
        numericParam("target").foreach(t => setup = setup.add(PositionAxisCommand.targetKey.set(t.toFloat)))

      case "offsetAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(OffsetAxisCommand.axisKey.set(a)))
        // ICD uses "distance" not "target" for offsetAxis
        numericParam("target").foreach(d => setup = setup.add(OffsetAxisCommand.distanceKey.set(d.toFloat)))

      case "selectWheel" =>
        stringParam("axis").foreach(a => setup = setup.add(SelectWheelCommand.axisKey.set(a)))
        // ICD uses IntKey "position" (wheel slot number)
        intParam("target").foreach(p => setup = setup.add(SelectWheelCommand.positionKey.set(p)))

      case "positionWheel" =>
        stringParam("axis").foreach(a => setup = setup.add(PositionWheelCommand.axisKey.set(a)))
        // ICD uses FloatKey "position" (angular position in degrees)
        numericParam("target").foreach(p => setup = setup.add(PositionWheelCommand.positionKey.set(p.toFloat)))

      case "configAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(ConfigAxisCommand.axisKey.set(a)))
        numericParam("velocity").foreach(v => setup = setup.add(ConfigAxisCommand.velocityKey.set(v.toFloat)))
        numericParam("acceleration").foreach(v => setup = setup.add(ConfigAxisCommand.accelerationKey.set(v.toFloat)))
        numericParam("deceleration").foreach(v => setup = setup.add(ConfigAxisCommand.decelerationKey.set(v.toFloat)))
        numericParam("indexOffset").foreach(v => setup = setup.add(ConfigAxisCommand.indexOffsetKey.set(v.toFloat)))
        numericParam("indexSpeed").foreach(v => setup = setup.add(ConfigAxisCommand.indexSpeedKey.set(v.toFloat)))
        numericParam("inPositionThreshold").foreach(v => setup = setup.add(ConfigAxisCommand.inPositionThresholdKey.set(v.toFloat)))

      case "configRotatingAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(ConfigRotatingAxisCommand.axisKey.set(a)))
        stringParam("algorithm").foreach(a => setup = setup.add(ConfigRotatingAxisCommand.algorithmKey.set(a)))

      case "configLinearAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(ConfigLinearAxisCommand.axisKey.set(a)))
        numericParam("upperLimit").foreach(v => setup = setup.add(ConfigLinearAxisCommand.upperLimitKey.set(v.toFloat)))
        numericParam("lowerLimit").foreach(v => setup = setup.add(ConfigLinearAxisCommand.lowerLimitKey.set(v.toFloat)))

      case "setBit" =>
        intParam("address").foreach(a => setup = setup.add(SetBitCommand.addressKey.set(a)))
        intParam("value").foreach(v => setup = setup.add(SetBitCommand.valueKey.set(v)))

      case "faultReset" =>
        // severity defaults to "None" if not provided; least intrusive recovery
        val severity = stringParam("severity").getOrElse("None")
        setup = setup.add(FaultResetCommand.severityKey.set(severity))

      case other =>
        throw new IllegalArgumentException(s"Unknown command: $other")
    }

    setup
  }

  // ── Server lifecycle ────────────────────────────────────────────────

  private var bindingFuture: Option[Future[Http.ServerBinding]] = None

  def start(): Unit = {
    // Wire HmiLogAppender broadcast; now all CSW log messages (including
    // [GALIL:prefix] controller console lines from ControllerConsoleActor)
    // stream to connected HMI clients as "logLine" WebSocket messages.
    HmiLogAppender.broadcast = broadcastLogLine

    val classicSystem = system.classicSystem
    bindingFuture = Some(
      Http()(classicSystem).newServerAt("0.0.0.0", port).bind(routes)
    )
    bindingFuture.get.onComplete {
      case Success(binding) =>
        val port = binding.localAddress.getPort
        log.info(s"HMI server started at http://localhost:$port/")
        log.info(s"  WebSocket: ws://localhost:$port/ws/state")
        log.info(s"  REST API:  http://localhost:$port/api/command")
      case Failure(ex) =>
        log.error(s"HMI server failed to start on port $port: ${ex.getMessage}")
    }
  }

  /**
   * Stop the HMI HTTP server and return a Future that completes when all
   * existing connections are actually closed.
   *
   * Uses `ServerBinding.terminate(hardDeadline)` rather than `unbind()`:
   *   - unbind() only stops accepting new connections; existing ones (the
   *     persistent browser keep-alive HTTP connection and the WebSocket)
   *     remain alive until the client closes them.  On Restart this matters
   *     because the new HmiServer's bind() succeeds even though the old
   *     binding still holds open connections; and HTTP requests on those
   *     persistent connections continue to be routed by the OLD binding's
   *     route closures, which capture the OLD (now-terminated) actor refs.
   *     Result: post-Restart commands fail with "InternalStateActor had
   *     already been terminated."
   *   - terminate(hardDeadline) actively closes existing connections after
   *     the deadline, which forces the browser to reconnect; and the next
   *     connection lands on the new binding with the new actor refs.
   *
   * The hard deadline is short (2s) because:
   *   1. We're tearing down for either a Shutdown (HCD is stopping) or a
   *      Restart (we want the new server up quickly); neither needs a
   *      lengthy in-flight-request grace period.
   *   2. Real HMI requests are sub-second; if anything is in flight at
   *      this moment it's almost certainly stalled.
   *
   * Caller (onShutdown) MUST await this Future before returning, otherwise
   * a subsequent initialize() may bind a second server on the same port
   * before this one fully releases.
   */
  def stop(): Future[Unit] = {
    // Disable log appender broadcast before teardown
    HmiLogAppender.clearBroadcast()

    // Cancel the WebSocket state push timer
    wsCancellable.cancel()

    bindingFuture match {
      case Some(bf) =>
        bf.flatMap(_.terminate(hardDeadline = 2.seconds)).map { _ =>
          log.info("HMI server stopped")
          ()
        }
      case None =>
        Future.successful(())
    }
  }
}