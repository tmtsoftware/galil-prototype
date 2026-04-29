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
  hcdPrefix: Prefix,
  log: Logger,
  locationService: LocationService,
  componentInfo: ComponentInfo,
  port: Int = 9090
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

  private val wsCancellable = system.scheduler.scheduleWithFixedDelay(
    initialDelay = wsUpdateInterval,
    delay = wsUpdateInterval
  )(() => {
    import AskPattern._
    internalStateActor
      .ask[HcdState](ref => InternalStateActor.GetHcdState(ref))(timeout, system.scheduler)
      .foreach { state =>
        wsPublisher ! stateToJson(state, hcdPrefix)
      }
  })(ec)

  // Register HmiLogAppender broadcast — the appender is instantiated by the CSW
  // framework before HmiServer exists, so wiring is done here at start time.
  // All HCD log messages (including [GALIL:prefix] controller console lines) will
  // be pushed to connected WebSocket clients as "logLine" JSON messages.
  private def broadcastLogLine(json: String): Unit = wsPublisher ! json

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

    // REST: initiate lifecycle Shutdown of the HCD component.
    // Resolves this component's own supervisor via the location service
    // and sends SupervisorContainerCommonMessages.Shutdown. The supervisor
    // stops the TLA, which triggers PostStop → lifecycleHandlers.onShutdown(),
    // and (since the HCD runs as a standalone process) terminates the JVM.
    //
    // Returns immediately after the message is sent; the actual teardown
    // happens asynchronously and the WebSocket will drop within a few seconds.
    // Available even when the HCD is in a Faulted state — Shutdown is an
    // admin operation, not a regular command, and is not gated.
    path("api" / "shutdown") {
      post {
        onComplete(handleShutdownRequest()) {
          case Success(msg) =>
            complete(HttpEntity(ContentTypes.`application/json`, s"""{"status":"$msg"}"""))
          case Failure(ex) =>
            log.error(s"HMI shutdown request failed: ${ex.getMessage}")
            complete(StatusCodes.InternalServerError,
              HttpEntity(ContentTypes.`application/json`, s"""{"error":"${ex.getMessage}"}"""))
        }
      }
    },

    // Static files: serve React SPA from resources/web/
    pathEndOrSingleSlash {
      getFromResource("web/index.html", ContentTypes.`text/html(UTF-8)`)
    },
    // Serve other static assets (JS, CSS, etc.)
    getFromResourceDirectory("web")
  )

  // ── WebSocket flow ──────────────────────────────────────────────────
  //
  // Incoming messages from the client are ignored (commands go via REST).
  // Outgoing: broadcast state updates as TextMessage frames.
  private def wsFlow: Flow[Message, Message, Any] = {
    Flow.fromSinkAndSource(
      Sink.ignore,
      wsSource.map(json => TextMessage.Strict(json): Message)
    )
  }

  // ── Log level helpers ───────────────────────────────────────────────

  // ── Command handling ────────────────────────────────────────────────

  /**
   * Resolve this component's own supervisor via the location service and
   * send SupervisorContainerCommonMessages.Shutdown. Returns a Future with
   * a status string on success, or fails with an exception on resolve error.
   */
  private def handleShutdownRequest(): Future[String] = {
    log.warn(s"HMI: Shutdown requested for ${componentInfo.prefix}")
    val connection = PekkoConnection(ComponentId(componentInfo.prefix, componentInfo.componentType))
    locationService.resolve(connection, 5.seconds).map {
      case Some(pekkoLocation) =>
        val supervisor: ActorRef[ComponentMessage] =
          pekkoLocation.uri.toActorRef.unsafeUpcast[ComponentMessage]
        supervisor ! SupervisorContainerCommonMessages.Shutdown
        log.info(s"HMI: Shutdown message sent to supervisor for ${componentInfo.prefix}")
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

      // Gate: if HCD is Faulted, only faultReset is permitted.
      // The HMI dispatches directly to CommandHandlerActor (bypassing CSW validateCommand),
      // so we must enforce the same Faulted check here.
      if request.commandName != "faultReset" then
        val hcdState = queryHcdStateForHmi()
        if hcdState.state == HcdStateEnum.Faulted then
          val reason = if hcdState.controllerErrorMsg.nonEmpty then hcdState.controllerErrorMsg
                       else "HCD is Faulted"
          log.warn(s"HMI command '${request.commandName}' rejected: $reason")
          return commandResponseJson(runId.id, "Error", s"HCD Faulted: $reason")

      // Build CSW Setup from JSON params using ICD key objects
      val setup = buildSetup(request.commandName, request.params, runId)

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
   * Synchronously query HcdState from InternalStateActor for HMI command gating.
   * Fails closed — on query failure returns Faulted so commands are blocked.
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

      case "trackAxis" =>
        stringParam("axis").foreach(a => setup = setup.add(TrackAxisCommand.axisKey.set(a)))
        numericParam("target").foreach(t => setup = setup.add(TrackAxisCommand.target1Key.set(t.toFloat)))
        numericParam("target2").foreach(t => setup = setup.add(TrackAxisCommand.target2Key.set(t.toFloat)))

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
        // severity defaults to "None" if not provided — least intrusive recovery
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
    // Wire HmiLogAppender broadcast — now all CSW log messages (including
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

  def stop(): Unit = {
    // Disable log appender broadcast before teardown
    HmiLogAppender.clearBroadcast()

    // Cancel the WebSocket state push timer
    wsCancellable.cancel()

    bindingFuture.foreach { bf =>
      bf.flatMap(_.unbind()).onComplete { _ =>
        log.info("HMI server stopped")
      }
    }
  }
}