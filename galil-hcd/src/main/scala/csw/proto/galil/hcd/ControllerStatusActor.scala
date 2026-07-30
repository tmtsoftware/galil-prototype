package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import csw.logging.client.scaladsl.LoggerFactory
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState}
import csw.proto.galil.io.{DataRecordFormatException, GalilIo, GalilIoTcp}

import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try
import csw.proto.galil.hcd.HcdStateEnum
import csw.time.core.models.TAITime

/**
 * ControllerStatusActor (SDD Section 4.6.5)
 *
 * Owns the status TCP connection to the Galil DMC-500x0 controller.
 * Opens its own GalilIo instance independently of ControllerCommandActor,
 * so QR and AI polls never contend with command traffic at the socket or
 * actor-mailbox level.
 *
 * Responsibilities:
 *   - Opens and maintains the status TCP handle (statusIo)
 *   - Polls the DataRecord (QR) at an adaptive rate (1Hz standby / 10Hz action)
 *   - Polls analog inputs (MG @AN[1..8]) at 1Hz independently
 *   - Parses DataRecord and updates InternalStateActor with positions, I/O, threads
 *
 * CS is a PURE OBSERVER of the thread lifecycle (ADR-001): it gathers raw
 * per-scan observations (_XQ-synthesized thread status for ALL 8 threads, the
 * observation timestamp, ae[] values, errorCode, eagerly-fetched TC text) and
 * ships them to IS in one ScanObservations message. All attribution —
 * completions and errors — happens in IS against its authoritative thread
 * registry, gated on observation freshness (observedAt vs registeredAt;
 * ADR-001 Amendment A). CS keeps no thread bookkeeping.
 */
object ControllerStatusActor:
  
  // Protocol
  sealed trait Command
  
  /**
   * Periodic polling trigger (internal timer message)
   */
  private[hcd] case object PollController extends Command

  /** Internal: result of the command-connection probe sent after a status IOException. */
  private case class CommandProbeResult(result: GalilCommandMessage.SendCommandResult) extends Command

  /**
   * Periodic analog input polling trigger (1Hz, independent of QR rate)
   * Package-private for test access.
   */
  private[hcd] case object PollAnalogInputs extends Command

  /**
   * Inject a parsed DataRecord directly (used in tests).
   */
  case class QRResponse(dataRecord: DataRecord) extends Command
  
  /**
   * Inject a QR error directly (used in tests).
   */
  case class QRError(error: String) extends Command
  
  /**
   * Command to start/stop polling
   */
  case class SetPolling(enabled: Boolean) extends Command

  /**
   * Declare whether the controller's embedded arrays (ae[] et al.) have been
   * dimensioned by #Init yet.  The per-scan ae[] read (readAeValues) must be
   * suppressed until they exist: on a freshly power-cycled controller whose
   * #AUTO no longer runs #Init, polling starts before #Init dimensions ae[],
   * so an `MG ae[i]` against the not-yet-created array makes the controller
   * latch error 57 ("Bad function or array").  The status actor swallows the
   * malformed read, but the latch survives and the post-#Init `TC 1` check in
   * GalilHcd then misattributes it to #Init and fails initialization.
   *
   * GalilHcd sends ready=false at the start of runInitSequence and ready=true
   * immediately after #Init completes (initController), so the suppression
   * window is exactly [start of (re)init .. ae[] dimensioned] on both the
   * initial and the recovery (Reset/Reload/Init) paths.
   */
  case class SetEmbeddedArraysReady(ready: Boolean) extends Command
  
  /**
   * Command to change polling rate
   */
  case class SetPollingRate(rateHz: Double) extends Command
  
  /**
   * Query current polling status
   */
  case class GetPollingStatus(replyTo: ActorRef[PollingStatus]) extends Command

  /**
   * Result of a Reconnect attempt.
   * @param success true if the status connection is now working
   * @param error   None on success, Some(description) on failure
   */
  case class ReconnectResult(success: Boolean, error: Option[String] = None)

  /**
   * Attempt to verify and if necessary re-establish the status TCP connection.
   *
   * Step 1: test the existing socket with a QR command.
   *   - If that succeeds the connection never actually dropped; report Connected,
   *     restart polling, done.
   * Step 2: if the test fails, close the dead socket and open a fresh GalilIoTcp.
   *   - Retest with QR. Report Connected + restart polling on success,
   *     Disconnected on failure.
   *
   * Used by faultReset (None severity) to recover from a detected connection loss.
   */
  case class Reconnect(replyTo: ActorRef[ReconnectResult]) extends Command
  
  /**
   * Internal: axis state changed notification from InternalStateActor.
   * Used to detect when axes transition between active/standby states
   * and adjust polling rate accordingly.
   */
  private[hcd] case class AxisStateChanged(stateChanged: InternalStateActor.StateChanged) extends Command

  /**
   * Advise CS whether IS's thread registry is non-empty (ADR-001 Amendment A).
   *
   * Feeds ONLY the polling-rate policy: action rate while any thread is
   * registered, so completion observation (and thus reservation release) is
   * bounded by one action-rate scan even for programs that never enter an
   * active axis state (e.g. #StopX on an idle axis).
   *
   * This replaced the per-thread AddScanThread/RemoveScanThread advisory set:
   * the scan now queries `MG _XQ0..7` unconditionally, so CS needs no thread
   * bookkeeping at all — the S85 storm showed any CS-side thread view can lag
   * the registry by seconds under mailbox pressure, and freshness is instead
   * carried per-observation (ScanObservations.observedAt).
   *
   * Sent by IS on registry empty↔non-empty transitions, never by external
   * callers.
   */
  case class ThreadRegistryActivity(active: Boolean) extends Command

  /**
   * Response to GetPollingStatus
   */
  case class PollingStatus(
    enabled: Boolean,
    rateHz: Double,
    lastPollTime: Option[Long],
    errorCount: Int
  )
  
  /**
   * Create ControllerStatusActor.
   *
   * In production (simulate=false) the actor opens its own GalilIoTcp connection.
   * In test mode (simulate=true or when a mock statusIo is injected via the
   * test-only overload below) it uses the provided GalilIo instead.
   *
   * @param galilConfig          Host/port for the status TCP connection
   * @param internalState        Actor to update with parsed data
   * @param loggerFactory        CSW logger factory
   * @param configuredAxes       Set of axes configured for use (used to scope ae[] reads)
   * @param standbyPollingRateHz Polling rate when all axes idle (default: 1Hz)
   * @param actionPollingRateHz  Polling rate when any axis active (default: 10Hz)
   */
  def apply(
    galilConfig: GalilConfig,
    internalState: ActorRef[InternalStateActor.Command],
    loggerFactory: LoggerFactory,
    commandActor: ActorRef[GalilCommandMessage],
    configuredAxes: Set[Axis],
    standbyPollingRateHz: Double = 1.0,
    actionPollingRateHz: Double = 10.0,
    // Per-scan position recorder for the HMI engineering plot (ADR-002).  Write-only
    // from CS's perspective: CS never reads it back, and it can influence neither
    // attribution nor thread lifecycle, so it does not compromise CS's pure-observer
    // role under ADR-001.  Optional so tests and any future non-HMI deployment can
    // omit it entirely.
    positionHistory: Option[PositionHistoryBuffer] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val statusIo: GalilIo = GalilIoTcp(galilConfig.host, galilConfig.port)
        new ControllerStatusActor(context, timers, statusIo, galilConfig, internalState,
          loggerFactory, standbyPollingRateHz, actionPollingRateHz, commandActor, configuredAxes,
          positionHistory = positionHistory)
      }
    }

  /**
   * Test-only factory: accepts a pre-built GalilIo (mock or real) instead of
   * opening a new TCP connection. Used by ControllerStatusActorTest and IOTest.
   */
  private[hcd] def withIo(
    statusIo: GalilIo,
    internalState: ActorRef[InternalStateActor.Command],
    loggerFactory: LoggerFactory,
    standbyPollingRateHz: Double = 1.0,
    actionPollingRateHz: Double = 10.0,
    configuredAxes: Set[Axis] = Set.empty,
    // Tests model a running, post-#Init controller by default (ae[] dimensioned),
    // so ae[] reads are enabled.  Pass false to exercise the startup-suppression
    // gate (ae[] reads withheld until SetEmbeddedArraysReady(true)).
    embeddedArraysReady: Boolean = true
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // Dummy galilConfig for test; reconnect is not exercised in unit tests
        val testConfig = GalilConfig("127.0.0.1", 8888)
        // Dummy command actor for test; probe is not exercised in unit tests
        val dummyCommandActor = context.system.deadLetters.asInstanceOf[ActorRef[GalilCommandMessage]]
        new ControllerStatusActor(context, timers, statusIo, testConfig, internalState,
          loggerFactory, standbyPollingRateHz, actionPollingRateHz, dummyCommandActor, configuredAxes,
          initialEmbeddedArraysReady = embeddedArraysReady)
      }
    }

/**
 * Actor implementation
 */
class ControllerStatusActor(
  context: ActorContext[ControllerStatusActor.Command],
  timers: TimerScheduler[ControllerStatusActor.Command],
  initialStatusIo: GalilIo,
  galilConfig: GalilConfig,
  internalState: ActorRef[InternalStateActor.Command],
  loggerFactory: LoggerFactory,
  standbyPollingRateHz: Double,
  actionPollingRateHz: Double,
  commandActor: ActorRef[GalilCommandMessage],
  configuredAxes: Set[Axis],
  // Initial value of embeddedArraysReady.  Production leaves this false (the
  // safe default; see the var below); the test factory defaults it true so
  // unit tests model a controller whose #Init has already dimensioned ae[].
  initialEmbeddedArraysReady: Boolean = false,
  // Per-scan position recorder for the HMI engineering plot (ADR-002).  Write-only
  // from this actor's perspective and consulted nowhere in the attribution path, so
  // it does not compromise CS's pure-observer role under ADR-001.  Defaults to None
  // so the test factory and any non-HMI deployment simply record nothing.
  positionHistory: Option[PositionHistoryBuffer] = None
) extends AbstractBehavior[ControllerStatusActor.Command](context):

  import ControllerStatusActor._

  // Mutable socket reference; replaced on successful reconnect
  private var statusIo: GalilIo = initialStatusIo

  private val log = loggerFactory.getLogger(context)

  // Active axis states that require action polling rate
  private val ActiveAxisStates: Set[AxisStateEnum] =
    Set(AxisStateEnum.Homing, AxisStateEnum.Moving, AxisStateEnum.Tracking)
  
  // Current state (mutable, but only accessed within actor)
  private var pollingEnabled: Boolean = true
  private var pollingRateHz: Double = standbyPollingRateHz  // Start at standby
  private var lastPollTime: Option[Long] = None
  private var errorCount: Int = 0

  // Reusable per-scan row for the ADR-002 position recorder, indexed by Axis.index.
  // A field rather than a per-scan allocation because this is written on the QR scan
  // thread; PositionHistoryBuffer.record copies the values out, so reuse is safe.
  // Sized to the maximum axis count so a controller's configured axis set can change
  // without touching this.
  private val positionScratch: Array[Double] = Array.fill(PositionHistoryBuffer.AxisCount)(Double.NaN)

  // True once #Init has dimensioned the controller's embedded arrays (ae[] et
  // al.).  Gates the per-scan ae[] read so it is never issued against a
  // not-yet-created array (which would latch controller error 57 and fail the
  // post-#Init TC 1 check).  Set via SetEmbeddedArraysReady from GalilHcd:
  // false at the start of (re)init, true right after #Init.  Production seeds
  // it false (initialEmbeddedArraysReady default) so a freshly-constructed
  // actor (initial startup, or a Restart-spawned TLA) does not read ae[]
  // before the first #Init; the test factory seeds it true.
  private var embeddedArraysReady: Boolean = initialEmbeddedArraysReady

  // --- Status-connection timeout resilience -------------------------------
  // A read timeout (SocketTimeoutException) means the controller did not reply
  // within the socket read timeout; NOT that the socket is dead.  A single slow
  // read was observed faulting a healthy controller for hours; the recovery found
  // the same socket still working.  We now drain any late response (to avoid a
  // desync on the next read) and tolerate a brief run of timeouts, escalating to
  // Faulted only on the MaxConsecutiveTimeouts-th in a row.  Each timeout blocks
  // the actor for up to the socket read timeout (~3s), so the threshold is kept
  // small.  Per-poll-type counts (QR vs AI) so each escalates independently; the
  // analog channels may be live control inputs; and a single successful read of
  // that type resets its count.  A genuine disconnect (remote close / broken pipe)
  // is a distinct exception and still faults immediately.
  private val MaxConsecutiveTimeouts = 2
  private var qrTimeoutCount: Int = 0
  private var aiTimeoutCount: Int = 0

  // A malformed/misaligned QR DataRecord (DataRecordFormatException) is a different
  // failure from a timeout: data IS arriving, but the receive buffer is dirty with
  // stale/partial bytes; typically right after a brief link interruption.  It is
  // recoverable by draining the buffer to resync, so we tolerate a few in a row and
  // fault only if the controller is persistently streaming garbage that draining
  // cannot fix.  The threshold is a little more lenient than for timeouts because a
  // single dirty record is the normal artifact of a reconnect, not an outage.
  private val MaxConsecutiveFormatErrors = 3
  private var qrFormatErrorCount: Int = 0

  // Kinds of optional-telemetry read (whlpos[], _PV/_BT) whose failure has already been
  // reported at WARN this session; see reportOptionalReadFailure.
  private val optionalReadFailuresReported = scala.collection.mutable.Set.empty[String]

  // Whether IS's thread registry is non-empty, per ThreadRegistryActivity
  // (ADR-001 Amendment A). Feeds ONLY the polling-rate policy. CS keeps no
  // per-thread bookkeeping: the scan queries all 8 threads unconditionally,
  // and attribution freshness travels with each observation (observedAt).
  private var registryActive: Boolean = false

  // All controller threads, queried by every scan (`MG _XQ0,...,_XQ7`). One
  // compound round-trip regardless of activity; querying unconditionally
  // removes any dependence of observation coverage on message timeliness.
  private val AllThreads: Set[Int] = (0 to 7).toSet

  // Cached axis-activity picture from the most recent AxisStateChanged, so
  // reevaluatePollingRate can be re-run on thread registration changes
  // without an hcdState in hand.
  private var anyAxisActive: Boolean = false
  private var activeAxesDesc: String = ""

  // Set of axes currently in axisState == Tracking, derived from the IS
  // StateChanged subscription.  Drives the per-scan PVT monitoring read
  // (`MG _PV<x>,_BT<x>` for each tracking axis).  Empty when no axis is
  // tracking, so the round-trip is skipped on cold poll cycles.
  private var trackingAxes: Set[Axis] = Set.empty

  // Subscribe to IS axisState changes via message adapter.  Drives polling-rate
  // adaptation (1Hz standby / 10Hz when any axis is Homing/Moving/Tracking) and
  // the trackingAxes cache for the per-scan PVT monitoring read.
  private val stateChangedAdapter = context.messageAdapter[InternalStateActor.StateChanged](
    sc => AxisStateChanged(sc)
  )
  internalState ! InternalStateActor.Subscribe(
    stateChangedAdapter,
    Some(InternalStateActor.FieldFilter(Set("axisState")))
  )
  
  // Start periodic polling at standby rate
  startPolling()
  // Start 1Hz analog input polling (independent of QR rate)
  timers.startTimerWithFixedDelay(PollAnalogInputs, 1.second)

  // Clear any stale controller error from previous sessions before polling starts.
  // A previous HCD session that ended with a connection loss (e.g. cable pull) may
  // have left a latched error (e.g. "123 TCP lost sync or timeout") in the controller.
  // Reading TC 1 now clears the latch so the first QR poll doesn't see a stale errorCode
  // and trigger a spurious fault. Logged at INFO for session diagnostics.
  try
    val responses = statusIo.send("TC 1")
    val tcText = responses.head._2.utf8String.trim
    if tcText.nonEmpty && tcText != "0" && !tcText.startsWith(" 0") then
      log.info(s"ControllerStatusActor: cleared stale controller error on connect — TC 1: '$tcText'")
    else
      log.info("ControllerStatusActor: TC 1 on connect — no stale controller error")
  catch
    case ex: Exception =>
      log.warn(s"ControllerStatusActor: TC 1 on connect failed: ${ex.getMessage}")

  // Report status connection established to InternalStateActor
  internalState ! InternalStateActor.ReportConnectionStatus(
    "statusConnection", ConnectionStatus.Connected
  )

  log.info(s"ControllerStatusActor started — standby: ${standbyPollingRateHz}Hz, action: ${actionPollingRateHz}Hz")
  
  override def onMessage(msg: Command): Behavior[Command] =
    msg match
      case PollController =>
        handlePollController()

      case PollAnalogInputs =>
        handlePollAnalogInputs()

      case QRResponse(dataRecord) =>
        handleQRResponse(dataRecord)
        
      case QRError(error) =>
        handleQRError(error)
        
      case SetPolling(enabled) =>
        handleSetPolling(enabled)

      case SetEmbeddedArraysReady(ready) =>
        if ready != embeddedArraysReady then
          log.info(s"Embedded arrays ${if ready then "ready" else "not ready"} — ae[] reads ${if ready then "enabled" else "suppressed"}")
        embeddedArraysReady = ready
        Behaviors.same
        
      case SetPollingRate(newRateHz) =>
        handleSetPollingRate(newRateHz)
        
      case GetPollingStatus(replyTo) =>
        replyTo ! PollingStatus(pollingEnabled, pollingRateHz, lastPollTime, errorCount)
        Behaviors.same

      case Reconnect(replyTo) =>
        handleReconnect(replyTo)

      case AxisStateChanged(stateChanged) =>
        handleAxisStateChanged(stateChanged)

      case ThreadRegistryActivity(active) =>
        log.debug(s"ThreadRegistryActivity: active=$active")
        registryActive = active
        reevaluatePollingRate()
        Behaviors.same

      case CommandProbeResult(result) =>
        if result.error.isDefined then
          log.error(s"Command connection probe after status loss — ALSO FAILED: ${result.error.get}. Controller is likely completely unreachable.")
        else
          log.error(s"Command connection probe after status loss — OK (response: '${result.response.trim}'). Status connection died in isolation; controller is still alive.")
        Behaviors.same
  
  /**
   * Attempt to verify and if necessary re-establish the status TCP connection.
   *
   * Step 1; verify existing socket: send QR and parse the DataRecord response.
   *   If this succeeds the connection was never truly lost (cable blip, OS recovery).
   *   Drain the receive buffer to discard any stale accumulated data before resuming
   *   polling, then report Connected and restart polling.
   *
   * Step 2; if step 1 fails: close the dead socket, open a fresh GalilIoTcp,
   *   retest with QR. On success: replace statusIo, drain buffer, report Connected,
   *   restart polling. On failure: report Disconnected, reply failure.
   *
   * Polling remains suspended during the reconnect attempt. On success it is
   * restarted at the current pollingRateHz.
   *
   * Buffer drain is critical: while polling was stopped the TCP receive buffer may
   * have accumulated stale QR responses or other controller output. Resuming polls
   * without draining causes DataRecord parse errors on the first post-recovery poll.
   */
  private def handleReconnect(replyTo: ActorRef[ReconnectResult]): Behavior[Command] =
    log.info(s"Reconnect: verifying status connection to ${galilConfig.host}:${galilConfig.port}")

    // Drain the receive buffer on the given socket.
    // Called both before testing (to clear stale data that might confuse the test)
    // and after a successful verify (to clear any remaining stale accumulation).
    def drainBuffer(io: GalilIo): Unit =
      val stale = io.drainAndShowBuffer(timeoutMs = 500)
      if stale.nonEmpty then
        log.info(s"Reconnect: drained ${stale.length} bytes of stale buffer data")

    // Clear the controller's TC error latch and log the result.
    // Called after any successful reconnect to consume any error recorded during
    // the disconnect event (e.g. "123 TCP lost sync or timeout"). This prevents
    // the first post-recovery QR poll from seeing a stale errorCode and shipping
    // spurious error evidence to IS. (The faulted-state gate itself lives in IS,
    // which suppresses attribution while HcdState is Faulted; ADR-001.)
    def clearTcLatch(io: GalilIo): Unit =
      try
        val responses = io.send("TC 1")
        val tcText = responses.head._2.utf8String.trim
        if tcText.nonEmpty && tcText != "0" && !tcText.startsWith(" 0") then
          log.info(s"Reconnect: cleared controller error latch — TC 1: '$tcText' (expected after disconnect event)")
        else
          log.info("Reconnect: TC 1 — no latched controller error")
      catch
        case ex: Exception =>
          log.warn(s"Reconnect: TC 1 failed: ${ex.getMessage}")

    def testCurrentSocket(): Boolean =
      try
        // Pre-drain before testing: stale buffered data from before the fault
        // could be misread as a valid test response. Drain first for a clean read.
        drainBuffer(statusIo)
        val response = statusIo.send("QR")
        val bs = response.head._2
        DataRecord(bs) // parse succeeds = connection alive
        true
      catch
        case _: Exception => false

    def openFreshSocket(): Either[String, GalilIo] =
      try
        val newIo = GalilIoTcp(galilConfig.host, galilConfig.port)
        val response = newIo.send("QR")
        val bs = response.head._2
        DataRecord(bs)
        Right(newIo)
      catch
        case ex: Exception =>
          Left(s"Failed to open new status connection: ${ex.getMessage}")

    // Step 1: test existing socket (pre-drain inside testCurrentSocket)
    if testCurrentSocket() then
      log.info("Reconnect: existing status socket is working")
      // Post-drain: clear any remaining stale data after the test QR
      drainBuffer(statusIo)
      clearTcLatch(statusIo)
      pollingEnabled = true
      startPolling()
      internalState ! InternalStateActor.ReportConnectionStatus(
        "statusConnection", ConnectionStatus.Connected
      )
      replyTo ! ReconnectResult(success = true)
    else
      // Step 2: close dead socket, open fresh one
      log.info("Reconnect: existing socket unresponsive — closing and opening new connection")
      try statusIo.close() catch case _: Exception => ()

      openFreshSocket() match
        case Right(newIo) =>
          statusIo = newIo
          drainBuffer(statusIo)
          clearTcLatch(statusIo)
          pollingEnabled = true
          startPolling()
          log.info("Reconnect: new status connection established — polling resumed")
          internalState ! InternalStateActor.ReportConnectionStatus(
            "statusConnection", ConnectionStatus.Connected
          )
          replyTo ! ReconnectResult(success = true)

        case Left(errMsg) =>
          log.error(s"Reconnect: $errMsg")
          internalState ! InternalStateActor.ReportConnectionStatus(
            "statusConnection", ConnectionStatus.Disconnected
          )
          replyTo ! ReconnectResult(success = false, error = Some(errMsg))

    Behaviors.same

  /**
   * Common handling for a read timeout on a status poll (QR or AI).
   *
   * SocketTimeoutException means no reply within the socket read timeout, not a
   * dead socket.  Drain any late/stale response (bounded) so the next read does
   * not desync, then either tolerate it (the next scheduled poll is the retry) or,
   * once `consecutiveCount` reaches MaxConsecutiveTimeouts, escalate to Faulted with
   * an accurate reason and stop polling.
   */
  private def handleStatusTimeout(pollLabel: String, consecutiveCount: Int, detail: String): Unit =
    val stale = statusIo.drainAndShowBuffer(timeoutMs = 500)
    if stale.nonEmpty then
      log.warn(s"$pollLabel poll timeout — drained ${stale.length} bytes of late/stale data (avoids next-read desync)")
    if consecutiveCount >= MaxConsecutiveTimeouts then
      log.error(s"$pollLabel poll — status connection unresponsive: $consecutiveCount consecutive read timeouts ($detail) — faulting")
      stopPolling()
      pollingEnabled = false
      // Mirror what ReportConnectionStatus(Disconnected) does internally (set the
      // field, then fault), but the socket is unresponsive rather than closed, so
      // the reason says so instead of claiming a disconnect that did not occur.
      internalState ! InternalStateActor.UpdateHcdState(
        Map("statusConnection" -> ConnectionStatus.Disconnected),
        context.system.ignoreRef
      )
      internalState ! InternalStateActor.EnterFaulted(
        s"Status connection unresponsive: $consecutiveCount consecutive $pollLabel read timeouts"
      )
    else
      log.warn(s"$pollLabel poll timeout ($consecutiveCount/$MaxConsecutiveTimeouts) — tolerating; next poll will retry: $detail")

  /**
   * Handle a malformed/misaligned QR DataRecord (DataRecordFormatException).
   *
   * Data is arriving but the receive buffer is dirty (stale/partial bytes, e.g. just
   * after a brief link blip).  Drain to resync; because data is flowing again the
   * link is back, so clear the timeout count.  Tolerate up to MaxConsecutiveFormatErrors
   * consecutive malformed records, escalating only if draining cannot resync (the
   * controller is persistently streaming garbage).
   */
  private def handleStatusFormatError(detail: String): Unit =
    val stale = statusIo.drainAndShowBuffer(timeoutMs = 500)
    qrTimeoutCount = 0  // data is flowing again — the link is back, not timed out
    qrFormatErrorCount += 1
    val drainedNote = if stale.nonEmpty then s", drained ${stale.length} stale bytes" else ""
    if qrFormatErrorCount >= MaxConsecutiveFormatErrors then
      log.error(s"QR — status connection: $qrFormatErrorCount consecutive malformed data records$drainedNote ($detail) — faulting")
      stopPolling()
      pollingEnabled = false
      internalState ! InternalStateActor.UpdateHcdState(
        Map("statusConnection" -> ConnectionStatus.Disconnected),
        context.system.ignoreRef
      )
      internalState ! InternalStateActor.EnterFaulted(
        s"Status connection: $qrFormatErrorCount consecutive malformed QR data records"
      )
    else
      log.warn(s"QR malformed record ($qrFormatErrorCount/$MaxConsecutiveFormatErrors)$drainedNote — resyncing; next poll will retry: $detail")

  private def handlePollController(): Behavior[Command] =
    if pollingEnabled then
      log.debug("Polling controller for QR data")
      try
        val response = statusIo.send("QR")
        val bs = response.head._2
        val dr = DataRecord(bs)
        qrTimeoutCount = 0      // primary QR read succeeded — clear consecutive-timeout count
        qrFormatErrorCount = 0  // valid record parsed — clear malformed-record count
        handleQRResponse(dr)
      catch
        case ex: java.net.SocketTimeoutException =>
          // Slow reply, socket likely alive; tolerate up to MaxConsecutiveTimeouts.
          qrTimeoutCount += 1
          handleStatusTimeout("QR", qrTimeoutCount, ex.getMessage)
          Behaviors.same
        case ex: DataRecordFormatException =>
          // Malformed/misaligned record; data is flowing but the buffer is dirty
          // (e.g. stale bytes after a brief link blip).  Drain + resync + tolerate;
          // a dirty record is not a connection loss.
          handleStatusFormatError(ex.getMessage)
          Behaviors.same
        case ex: java.io.IOException =>
          // Genuine disconnect (remote close → read -1, or broken pipe), not a
          // timeout: the socket is gone.  Report Disconnected (→ Faulted) and probe
          // the command connection to distinguish isolated status loss from total
          // controller loss.  (Auto-reconnect on true disconnect is a future item.)
          log.error(s"QR poll — status connection lost: ${ex.getMessage}")
          stopPolling()
          pollingEnabled = false
          internalState ! InternalStateActor.ReportConnectionStatus(
            "statusConnection", ConnectionStatus.Disconnected
          )
          // Probe the command connection to distinguish an isolated status connection
          // failure from total controller loss. Result logged via CommandProbeResult.
          val probeAdapter = context.messageAdapter[GalilCommandMessage.SendCommandResult](CommandProbeResult(_))
          commandActor ! GalilCommandMessage.SendCommand("MG 1", probeAdapter)
          Behaviors.same
        case ex: Exception =>
          log.error(s"QR poll failed (non-IO): ${ex.getMessage}")
          errorCount += 1
          Behaviors.same
    else
      Behaviors.same
  
  /**
   * Handle QR response from controller.
   *
   * CS is a PURE OBSERVER (ADR-001): this scan gathers raw observations and
   * ships them to IS in a single ScanObservations message. ALL attribution —
   * program completions and error decisions alike — happens in IS against its
   * authoritative thread registry, under one freshness gate. CS makes no
   * attribution decisions and pushes no axisErrorMsg/axisState=Error updates.
   *
   * Per scan, in order:
   *   1. Parse QR DataRecord; captures threadStatus and errorCode at one moment.
   *   2. Stamp observedAt (monotonic nanoTime), then query per-thread execution
   *      state via MG _XQ0..7 (ALL threads, unconditionally) and synthesize the
   *      threadStatus byte. The stamp is taken BEFORE the read, so it can only
   *      understate the observation's freshness — IS's staleness gate
   *      (observedAt > entry.registeredAt) stays conservative.
   *   3. Read MG ae[i] for each configured axis (single compound read). Done after
   *      QR so that ae values reflect any errors that arose during this scan;
   *      reversing the order would race with successful program endings.
   *   4. If errorCode != 0: eagerly fetch TC 1 (consumes the controller latch)
   *      and carry the text in the observation message. IS holds the text
   *      across its one-scan attribution deferral, so the hardware latch no
   *      longer needs to stay set for the retry.
   *   5. Push HCD-level + per-axis QR-derived updates (position, velocity,
   *      switches, moving). Sent BEFORE ScanObservations so the watcher's
   *      inPosition/moving picture is fresh when IS attributes completions.
   *   6. Ship ScanObservations to IS. IS sequences error attribution before
   *      completion attribution internally, preserving the contract that a
   *      watcher sees axisErrorMsg before activeThread→-1. Delivery latency is
   *      harmless: a delayed observation is EXCLUDED by IS's staleness gate
   *      for any entry registered after observedAt, never misapplied (the S85
   *      storm delivered scans up to ~1.4s late under mailbox pressure).
   */
  private def handleQRResponse(dataRecord: DataRecord): Behavior[Command] =
    try
      // One wall-clock stamp for the whole scan.  Shared by lastPollTime and by the
      // ADR-002 position sample so the plot's time base is exactly the poll time the
      // HMI already reports, rather than a second, slightly later clock read.
      val scanTimeMs = System.currentTimeMillis()
      lastPollTime = Some(scanTimeMs)
      errorCount = 0  // Reset error count on success

      log.debug(s"Received QR data, sample: ${dataRecord.generalState.sampleNumber}")

      val activeAxisChars = dataRecord.header.blocksPresent.filter(axis => DataRecord.axes.contains(axis))
      val rawThreadStatusByte = dataRecord.generalState.threadStatus & 0xFF
      val rawErrorCode        = dataRecord.generalState.errorCode    & 0xFF

      // Step 2: query per-thread execution state via MG _XQ0..7 — ALL threads,
      // unconditionally (ADR-001 Amendment A). One compound round-trip; no
      // dependence on any CS-side thread bookkeeping that could lag under
      // mailbox pressure.
      //
      // Why not use rawThreadStatusByte (from QR)?
      //   Empirically, on this controller firmware, after CMDERR halts a thread,
      //   the thread bit in QR's threadStatus byte (and in `MG _NO`) can remain
      //   set for many seconds; until other unrelated activity settles. This
      //   is especially common when other threads are concurrently running
      //   motion. _XQ<n> is the authoritative per-thread status: -1 means
      //   stopped, regardless of what _NO claims.
      //
      // observedAt is stamped BEFORE the read so it can only understate the
      // observation's freshness; IS's staleness gate compares it against each
      // registry entry's registeredAt (monotonic nanoTime, same JVM).
      val observedAt: Long = System.nanoTime()
      val xqValues: Map[Int, Int] = readXqValues(AllThreads)

      // Synthesize a threadStatus byte from _XQ results: bit N set if _XQ<n>
      // returned a non-(-1) value (thread is executing).
      // If readXqValues returned short/empty (e.g. parse failure) we fall back
      // to the raw QR byte to fail-closed (stale-active bits in the raw byte
      // can only delay attribution by a scan, never fabricate one).
      val threadStatusByte: Int =
        if xqValues.size != AllThreads.size then
          log.debug(s"_XQ query returned ${xqValues.size} of ${AllThreads.size} expected; " +
            s"falling back to QR threadStatus byte 0x${rawThreadStatusByte.toHexString}")
          rawThreadStatusByte
        else
          AllThreads.foldLeft(0) { (acc, thread) =>
            if xqValues.getOrElse(thread, -1) != -1 then acc | (1 << thread)
            else acc
          }

      // Step 3: read ae[] for configured axes (graceful on parse failure).
      val aeValues: Map[Axis, Int] = readAeValues()

      // Step 3b: PVT monitoring read for any axis currently tracking.  Single
      // compound MG _PV<x>,_BT<x>,... round-trip; skipped if no axis is tracking.
      // The observedAt timestamp is captured close to the read so the IS-side
      // TAI comparison uses the moment the controller actually responded.
      if trackingAxes.nonEmpty then
        val pvtObservedAt = TAITime.now().value
        val pvtReadings = readPvtMonitoring(trackingAxes)
        if pvtReadings.nonEmpty then
          internalState ! InternalStateActor.ReportPvtMonitoring(pvtReadings, pvtObservedAt)

      // Step 4: eager TC fetch on a latched controller error. Consuming the
      // latch here is safe because IS (not the hardware latch) carries the
      // error evidence across its one-scan deferral; and it is self-limiting
      // because consumption clears QR's errorCode for subsequent scans (a
      // re-latch by a running embedded error handler simply repeats the fetch
      // with fresh text). Fetched BEFORE the observation message is built so
      // the text and the errorCode travel together.
      val tcText: Option[String] =
        if rawErrorCode != 0 then Some(fetchTcMessage(rawErrorCode))
        else None

      // Diagnostic: when an error is latched, log the raw observation inputs
      // so post-mortem analysis of any unexpected fault is possible without
      // instrumenting further. Cheap (only fires on errorCode != 0) and at
      // DEBUG so it doesn't clutter normal logs. (The attribution decision and
      // its forensic snapshot now live in IS; ADR-001.)
      if rawErrorCode != 0 then
        log.debug(s"QR scan with errorCode=$rawErrorCode: " +
          s"threadStatusByte=0x${threadStatusByte.toHexString} " +
          s"(raw QR=0x${rawThreadStatusByte.toHexString}, _XQ=$xqValues), " +
          s"aeValues=$aeValues, tcText=$tcText")

      // Step 5: HCD-level + per-axis updates from QR. Before ScanObservations
      // so the watcher's inPosition/moving picture is fresh at attribution.
      updateHcdState(dataRecord.generalState, activeAxisChars, scanTimeMs)
      // Reset the ADR-002 scratch row to "absent" before the loop; axes not present in
      // this scan stay NaN, which the encoders render as null/empty rather than 0.
      java.util.Arrays.fill(positionScratch, Double.NaN)
      activeAxisChars
        .zip(dataRecord.axisStatuses)
        .foreach { case (axisChar, axisStatus) =>
          // updateAxisState returns the position it pushed to IS (stepper vs servo
          // branch) so the recorder cannot drift into a second definition of what
          // "position" means for an axis.
          val pos = updateAxisState(axisChar, axisStatus)
          val idx = Axis.fromChar(axisChar).index
          if (idx >= 0 && idx < positionScratch.length) positionScratch(idx) = pos
        }
      // One sample per scan for all axes under the single scan timestamp (ADR-002).
      // O(8), allocation free, brief lock -- this is the QR scan thread.  positionScratch
      // is reused every scan; record() copies the values out.
      positionHistory.foreach(_.record(scanTimeMs, positionScratch))

      // Step 6: ship the complete observation set to IS in one message.
      // We send the SYNTHESIZED byte (built from per-thread _XQ<n> queries
      // above) rather than the raw QR threadStatus byte, so IS sees accurate
      // per-thread state even when the QR byte is stuck stale post-CMDERR.
      // observedAt lets IS's staleness gate exclude this scan from any
      // registry entry registered after the _XQ read — however late the
      // message is delivered (ADR-001 Amendment A).
      internalState ! InternalStateActor.ScanObservations(
        threadStatusByte, observedAt, aeValues, rawErrorCode, tcText)

      Behaviors.same
    catch
      case ex: java.net.SocketTimeoutException =>
        // Mid-scan read timeout (ae[]/TC): tolerate exactly like the primary QR send.
        qrTimeoutCount += 1
        handleStatusTimeout("QR", qrTimeoutCount, ex.getMessage)
        Behaviors.same
      case ex: java.io.IOException =>
        // ae[] read or other status-connection I/O failed mid-scan.
        // Genuine loss (not a timeout): stop polling, report Disconnected,
        // probe the command connection. Mirrors handlePollController's catch.
        log.error(s"QR scan — status connection lost mid-scan: ${ex.getMessage}")
        stopPolling()
        pollingEnabled = false
        internalState ! InternalStateActor.ReportConnectionStatus(
          "statusConnection", ConnectionStatus.Disconnected
        )
        val probeAdapter = context.messageAdapter[GalilCommandMessage.SendCommandResult](CommandProbeResult(_))
        commandActor ! GalilCommandMessage.SendCommand("MG 1", probeAdapter)
        Behaviors.same
      case ex: Exception =>
        log.error(s"Error processing QR response: ${ex.getMessage}")
        errorCount += 1
        Behaviors.same

  /**
   * Issue a compound `MG` read and return the reply tokens in request order.
   *
   * The operand list is split across as many round trips as the controller's
   * 80-character command-line buffer requires (GalilIo.chunkMgOperands). This is not a
   * theoretical guard: GalilIo.send rejects an over-length line before writing it, and
   * `MG whlpos[0],...,whlpos[7]` is 82 characters, so on every 8-axis configuration the
   * achieved wheel-slot read threw, was swallowed as optional telemetry, and
   * wheelPosition stayed at -1 for the life of the HCD. Anything that scales with the
   * axis or thread count goes through here rather than building its own line.
   *
   * Tokens from the chunks are concatenated in request order, so each caller's
   * expected-token-count check still validates the read as a whole.
   *
   * Exceptions propagate: the caller decides whether a failed read means connection
   * loss (ae[], _XQ) or is optional telemetry (whlpos[], _PV/_BT).
   */
  private def mgReadTokens(operands: Seq[String]): Seq[String] =
    GalilIo.chunkMgOperands(operands).flatMap { cmd =>
      statusIo.send(cmd).map(_._2.utf8String).mkString.trim.split("\\s+").filter(_.nonEmpty).toSeq
    }

  /**
   * Report a failed optional-telemetry read: the first occurrence of each kind at WARN,
   * repeats at DEBUG.
   *
   * These reads are deliberately non-fatal, but "non-fatal" had meant invisible: the
   * over-length whlpos[] line failed identically on every poll for two commits at DEBUG
   * while the HMI showed a plausible "unknown slot". A non-I/O failure here is a defect
   * in what we asked for, not a controller condition, so it is said out loud once.
   */
  private def reportOptionalReadFailure(what: String, detail: String): Unit =
    if optionalReadFailuresReported.add(what) then
      log.warn(s"$what read failed and will be retried each cycle (reported once): $detail")
    else
      log.debug(s"$what read failed (ignored): $detail")

  /**
   * Read MG ae[i] for each configured axis and return a Map[Axis, Int].
   *
   * Issued as a compound `MG ae[0],ae[1],...` round-trip on the status connection
   * (chunked by mgReadTokens if the axis set makes the line too long). Returns Map.empty
   * on any parse or I/O failure; the simulator does not implement ae[] storage, and we do
   * not want to fault on its absence.
   *
   * IOExceptions propagate to handlePollController via handleQRResponse's catch
   * block (we let the QR loss handler deal with connection failures uniformly).
   */
  private def readAeValues(): Map[Axis, Int] =
    // Suppress until #Init has dimensioned ae[]: an `MG ae[i]` against a
    // not-yet-created array makes the controller latch error 57 ("Bad function
    // or array"), which the post-#Init TC 1 check then misattributes to #Init.
    // embeddedArraysReady is set false at the start of (re)init and true right
    // after #Init by GalilHcd (SetEmbeddedArraysReady).
    if !embeddedArraysReady || configuredAxes.isEmpty then Map.empty
    else
      val sortedAxes = configuredAxes.toSeq.sortBy(_.index)
      try
        val tokens = mgReadTokens(sortedAxes.map(a => s"ae[${a.index}]"))
        if tokens.length != sortedAxes.length then
          log.debug(s"ae[] read returned ${tokens.length} tokens, expected ${sortedAxes.length}; ignoring (text='${tokens.mkString(" ")}')")
          Map.empty
        else
          val parsed = sortedAxes.zip(tokens).flatMap { (axis, tok) =>
            Try(tok.toDouble.toInt).toOption.map(v => axis -> v)
          }.toMap
          if parsed.size != sortedAxes.length then
            log.debug(s"ae[] read produced unparseable tokens; ignoring (text='${tokens.mkString(" ")}')")
            Map.empty
          else
            parsed
      catch
        case ex: java.net.SocketTimeoutException =>
          throw ex  // preserve timeout type so the outer QR handler tolerates it
        case ex: java.io.IOException =>
          // Re-throw so the outer QR loop's IOException handler reports connection loss.
          throw new java.io.IOException(s"ae[] read failed (status connection): ${ex.getMessage}", ex)
        case ex: Exception =>
          log.debug(s"ae[] read failed (non-IO, ignored): ${ex.getMessage}")
          Map.empty

  /**
   * Read achieved wheel positions from the embedded `whlpos[]` array — one compound
   * MG round trip over the configured axes. Returns Map[axis -> slot], where slot is
   * the 1-based wheel position last confirmed by the controller's select logic, or -1
   * for "unknown" (no successful select since startup/home, or a non-wheel axis — the
   * embedded leaves those at -1). The caller applies whatever is reported, including
   * -1, so a transition back to "unknown" (e.g. the embedded invalidating the slot at
   * the start of a home/move) propagates correctly.
   *
   * Scoped to configuredAxes (so non-existent G/H are never touched, avoiding phantom
   * AxisState entries) and gated on embeddedArraysReady (so we never MG an array that
   * #Init has not dimensioned — the embedded program must declare `DM whlpos[8]` in
   * #Init, exactly as it does for ae[]). Unlike ae[], wheel-position readback is
   * optional telemetry: this NEVER rethrows, so a program that predates whlpos[] (or
   * any transient hiccup) yields Map.empty and leaves wheelPosition unchanged, never
   * disturbing the analog/QR connection-health machinery that already runs this tick.
   *
   * The operand list goes through mgReadTokens because `whlpos[i]` is nine characters:
   * eight axes make an 82-character line, which GalilIo.send refuses to write. Chunking
   * is what makes this read work at all on an 8-axis controller.
   */
  private def readWhlposValues(): Map[Axis, Int] =
    if !pollingEnabled || !embeddedArraysReady || configuredAxes.isEmpty then Map.empty
    else
      val sortedAxes = configuredAxes.toSeq.sortBy(_.index)
      try
        val tokens = mgReadTokens(sortedAxes.map(a => s"whlpos[${a.index}]"))
        if tokens.length != sortedAxes.length then
          log.debug(s"whlpos[] read returned ${tokens.length} tokens, expected ${sortedAxes.length}; ignoring (text='${tokens.mkString(" ")}')")
          Map.empty
        else
          sortedAxes.zip(tokens).flatMap { (axis, tok) =>
            Try(tok.toDouble.toInt).toOption.map(v => axis -> v)
          }.toMap
      catch
        case ex: Exception =>
          // Optional telemetry — never escalate (a program without whlpos[] lands here).
          reportOptionalReadFailure("whlpos[]", ex.getMessage)
          Map.empty

  /**
   * Read MG _XQ<n> for each requested thread (the per-scan caller passes
   * AllThreads) and return a Map[thread, line], where line is the current
   * execution line number, or -1 if the thread is stopped.
   *
   * Why this exists: empirically, after a CMDERR halts a thread on this
   * controller firmware, the thread bit in QR's `threadStatus` byte (and in
   * `MG _NO`) can remain set for many seconds; until other unrelated activity
   * settles. `_XQ<n>` is the authoritative per-thread status: -1 means the
   * thread has stopped, regardless of what _NO claims.
   *
   * Behavior:
   *   - If no threads are registered: skip the round-trip, return Map.empty.
   *   - On parse failure: returns Map.empty (treat as "couldn't determine"
   *    ; calling code falls back to QR's threadStatus, fail-closed).
   *   - On IOException: rethrows so the outer QR loop reports connection loss.
   *
   * Sorted by thread number so the response tokens line up with the request.
   */
  private def readXqValues(threads: Set[Int]): Map[Int, Int] =
    if threads.isEmpty then Map.empty
    else
      val sortedThreads = threads.toSeq.sorted
      try
        val tokens = mgReadTokens(sortedThreads.map(t => s"_XQ$t"))
        if tokens.length != sortedThreads.length then
          log.debug(s"_XQ read returned ${tokens.length} tokens, expected ${sortedThreads.length}; ignoring (text='${tokens.mkString(" ")}')")
          Map.empty
        else
          val parsed = sortedThreads.zip(tokens).flatMap { (thread, tok) =>
            Try(tok.toDouble.toInt).toOption.map(v => thread -> v)
          }.toMap
          if parsed.size != sortedThreads.length then
            log.debug(s"_XQ read produced unparseable tokens; ignoring (text='${tokens.mkString(" ")}')")
            Map.empty
          else
            parsed
      catch
        case ex: java.net.SocketTimeoutException =>
          throw ex  // preserve timeout type so the outer QR handler tolerates it
        case ex: java.io.IOException =>
          throw new java.io.IOException(s"_XQ read failed (status connection): ${ex.getMessage}", ex)
        case ex: Exception =>
          log.debug(s"_XQ read failed (non-IO, ignored): ${ex.getMessage}")
          Map.empty

  /**
   * Read `MG _PV<x>,_BT<x>` for each tracking axis and return a Map[Axis, (freeFifoSlots, segmentsExecuted)].
   *
   * Used to monitor an active PVT tracking session:
   *   - `_PV<x>` is the number of free segment slots in the per-axis PVT FIFO
   *     (255 = empty, 0 = full).  Drives optional watermark warnings.
   *   - `_BT<x>` is the count of segments executed since the most recent `BT<x>`.
   *     Monotonic within a session; useful for cross-checking against
   *     `TrackingSession.segmentsSubmitted` and as a diagnostic timing reference.
   *
   * Companion to `readAeValues` / `readXqValues`: single compound MG round-trip
   * on the status connection.  The IS-side underrun detector reads the
   * forwarded values relative to the per-session `lastValidTime`; we do not
   * make the determination here (interpretation belongs with the data; IS
   * owns the session ledger, S61 lesson).
   *
   * Behavior:
   *   - If no axes are tracking: skip the round-trip, return Map.empty.
   *   - On parse failure: returns Map.empty (the underrun detector will simply
   *     not get a fresh reading this cycle; preemptive detection still fires
   *     on the next clean read).
   *   - On IOException: rethrows so the outer QR loop reports connection loss.
   */
  private def readPvtMonitoring(axes: Set[Axis]): Map[Axis, (Int, Int)] =
    if axes.isEmpty then Map.empty
    else
      val sortedAxes = axes.toSeq.sortBy(_.index)
      try
        // Interleave _PV<x>,_BT<x> so token order matches axis order with stride 2.
        // Chunked for the same reason as whlpos[]: two 4-character operands per axis is
        // an 82-character line at eight tracking axes, which send refuses to write.
        val tokens = mgReadTokens(sortedAxes.flatMap(a => Seq(s"_PV${a.char}", s"_BT${a.char}")))
        val expected = sortedAxes.length * 2
        if tokens.length != expected then
          log.debug(s"_PV/_BT read returned ${tokens.length} tokens, expected $expected; ignoring (text='${tokens.mkString(" ")}')")
          Map.empty
        else
          val parsed = sortedAxes.zipWithIndex.flatMap { (axis, i) =>
            for
              pv <- Try(tokens(2 * i).toDouble.toInt).toOption
              bt <- Try(tokens(2 * i + 1).toDouble.toInt).toOption
            yield axis -> (pv, bt)
          }.toMap
          if parsed.size != sortedAxes.length then
            log.debug(s"_PV/_BT read produced unparseable tokens; ignoring (text='${tokens.mkString(" ")}')")
            Map.empty
          else
            parsed
      catch
        case ex: java.net.SocketTimeoutException =>
          throw ex  // preserve timeout type so the outer QR handler tolerates it
        case ex: java.io.IOException =>
          throw new java.io.IOException(s"_PV/_BT read failed (status connection): ${ex.getMessage}", ex)
        case ex: Exception =>
          reportOptionalReadFailure("_PV/_BT", ex.getMessage)
          Map.empty

  /**
   * Fetch and clear the controller error latch via TC 1. Returns the message
   * string (e.g. "22 Begin not possible due to limit switch"), or a fallback
   * description if the call fails or returns empty/zero.
   *
   * Called eagerly from the QR scan whenever errorCode != 0 (ADR-001): the
   * text travels to IS inside ScanObservations, and IS — not the hardware
   * latch — carries the evidence across its one-scan attribution deferral.
   */
  private def fetchTcMessage(rawErrorCode: Int): String =
    Try {
      val responses = statusIo.send("TC 1")
      val tcText = responses.head._2.utf8String.trim
      if tcText.isEmpty || tcText == "0" then
        s"$rawErrorCode (description unavailable)"
      else
        tcText
    }.getOrElse(s"$rawErrorCode (TC call failed)")

  /**
   * Handle QR error from controller
   */
  private def handleQRError(error: String): Behavior[Command] =
    log.error(s"QR request failed: $error")
    errorCount += 1
    Behaviors.same
  
  /**
   * Enable/disable polling
   */
  private def handleSetPolling(enabled: Boolean): Behavior[Command] =
    if enabled != pollingEnabled then
      pollingEnabled = enabled
      if enabled then
        log.info("QR polling enabled")
        startPolling()
      else
        log.info("QR polling disabled")
        stopPolling()
    Behaviors.same
  
  /**
   * Change polling rate
   */
  private def handleSetPollingRate(newRateHz: Double): Behavior[Command] =
    if newRateHz > 0 && newRateHz != pollingRateHz then
      pollingRateHz = newRateHz
      log.info(s"Polling rate changed to ${pollingRateHz}Hz (${pollingPeriod.toMillis}ms)")
      if pollingEnabled then
        stopPolling()
        startPolling()
    Behaviors.same
  
  /**
   * Handle axis state change notification from InternalStateActor.
   * 
   * Adapts polling rate based on aggregate axis activity:
   *   - If ANY axis is in an active state (Homing, Moving, Tracking) → action rate
   *   - If ALL axes are in standby states (Lost, Idle, Error) → standby rate
   * 
   * Also updates currentPollingRateHz in IS so it's visible to the rest of the system.
   */
  private def handleAxisStateChanged(stateChanged: InternalStateActor.StateChanged): Behavior[Command] =
    val hcdState = stateChanged.hcdState

    anyAxisActive = hcdState.axes.values.exists(ax => ActiveAxisStates.contains(ax.axisState))
    activeAxesDesc =
      if anyAxisActive then
        hcdState.axes.collect {
          case (axis, ax) if ActiveAxisStates.contains(ax.axisState) =>
            s"${axis}:${ax.axisState}"
        }.mkString(", ")
      else ""

    // Update cache of which axes are in Tracking, used by handleQRResponse to
    // decide whether to poll _PV<x>/_BT<x>.  Recomputed on every state change
    // (subscription is filtered to axisState changes only; cheap).
    trackingAxes = hcdState.axes.collect {
      case (axis, ax) if ax.axisState == AxisStateEnum.Tracking => axis
    }.toSet

    reevaluatePollingRate()
    Behaviors.same

  /**
   * Apply the polling-rate policy against the current activity picture.
   *
   * Action rate while ANY axis is in an active state (Homing, Moving,
   * Tracking) OR IS's thread registry is non-empty (ThreadRegistryActivity);
   * standby rate otherwise. The registry term matters for programs that never
   * enter an active axis state — e.g. #StopX on an already-idle axis — whose
   * completion must still be observed promptly: the CI actor's allocation
   * gate holds the thread reserved until a scan attributes the completion,
   * so observation latency directly bounds thread-pool turnaround.
   *
   * Called from handleAxisStateChanged (axis activity changes) and from the
   * ThreadRegistryActivity handler (registry empty↔non-empty transitions).
   */
  private def reevaluatePollingRate(): Unit =
    val anyActivity = anyAxisActive || registryActive
    val targetRate = if anyActivity then actionPollingRateHz else standbyPollingRateHz

    if targetRate != pollingRateHz then
      val reason =
        if anyAxisActive then s"active axes [$activeAxesDesc]"
        else if registryActive then "threads registered in IS"
        else "all axes standby, no registered threads"

      pollingRateHz = targetRate
      log.info(s"Polling rate → ${pollingRateHz}Hz ($reason)")
      if pollingEnabled then
        stopPolling()
        startPolling()
      
      // Update IS with current rate
      internalState ! InternalStateActor.UpdateHcdState(
        Map("currentPollingRateHz" -> pollingRateHz),
        context.system.ignoreRef
      )
  
  private def handlePollAnalogInputs(): Behavior[Command] =
    if !pollingEnabled then return Behaviors.same
    log.debug("AI poll: sending compound MG @AN query")

    // Single compound MG command; one round-trip instead of 8 sequential calls.
    // Hardware returns space-separated values on one line: "2.5839 2.5839 0.0000 ..."
    //
    // Called directly on statusIo on the actor thread; NOT in a Future.
    // Rationale: statusIo (a plain Socket) is not thread-safe. Running the AI poll
    // in a Future on the execution context created a data race with PollController,
    // which also calls statusIo.send() on the actor thread. Since PollController
    // and PollAnalogInputs are serialized by the actor mailbox, keeping both on the
    // actor thread eliminates the race with no additional synchronization.
    val mgCmd = s"MG ${(1 to 8).map(n => s"@AN[$n]").mkString(",")}"
    try
      val responses    = statusIo.send(mgCmd)
      aiTimeoutCount = 0  // successful read — clear consecutive-timeout count
      val responseText = responses.map(_._2.utf8String).mkString
      log.debug(s"AI poll response: '$responseText'")
      val tokens = responseText.trim.split("\\s+").filter(_.nonEmpty)
      val values = Array.tabulate(8) { i =>
        if i < tokens.length then Try(tokens(i).toFloat).getOrElse(0.0f)
        else 0.0f
      }
      internalState ! InternalStateActor.UpdateHcdState(
        Map("analogInputs" -> values),
        context.system.ignoreRef
      )
      // Achieved wheel positions from the embedded whlpos[] array, on the same 1 Hz
      // round-trip cadence (whlpos only changes on a select completion). Best-effort:
      // readWhlposValues never throws, so a controller/program without whlpos[] simply
      // leaves wheelPosition unchanged and never disturbs analog/QR connection health.
      readWhlposValues().foreach { (axis, v) =>
        internalState ! InternalStateActor.UpdateAxisState(
          axis, Map("wheelPosition" -> v), context.system.ignoreRef
        )
      }
    catch
      case ex: java.net.SocketTimeoutException =>
        // Slow reply, socket likely alive; tolerate up to MaxConsecutiveTimeouts.
        // AI escalates on its own count: these channels may be live control inputs,
        // so persistent AI loss must fault even if QR happens to stay healthy.
        aiTimeoutCount += 1
        handleStatusTimeout("AI", aiTimeoutCount, ex.getMessage)
      case ex: java.io.IOException =>
        // Genuine disconnect, not a timeout; socket is gone.
        log.error(s"AI poll — status connection lost: ${ex.getMessage}")
        stopPolling()
        pollingEnabled = false
        internalState ! InternalStateActor.ReportConnectionStatus(
          "statusConnection", ConnectionStatus.Disconnected
        )
      case ex: Exception =>
        log.warn(s"AI poll failed (non-IO): ${ex.getMessage}")
    Behaviors.same

  /**
   * Update HCD-level state from GeneralState (positions, I/O, timing).
   *
   * NOTE: Thread status and error evidence travel to IS via ScanObservations
   * (built in handleQRResponse); attribution happens in IS (ADR-001). This
   * method only publishes the bulk per-scan status updates.
   */
  private def updateHcdState(
    generalState: GeneralState,
    activeAxisChars: Seq[Char],
    // The scan's own timestamp, captured once in handleQRResponse.  Passed in rather
    // than read again here so lastPollingTime is genuinely the instant this data was
    // acquired -- it is now published as CurrentStateAxis.sampleTime, so a second
    // clock read would put a small, invisible skew between the value and its label.
    scanTimeMs: Long
  ): Unit =
    val threadStatusByte = generalState.threadStatus & 0xFF

    // inputs/outputs: 10 bytes in QR DataRecord.
    // The number of usable DI/DO channels depends on the controller model:
    //   DMC-50040 (4-axis): 8 DI, 8 DO  → only byte 0 is meaningful
    //   DMC-50080 (8-axis): 16 DI, 16 DO → bytes 0 and 1 are meaningful
    // We always expand to a 16-element Boolean array; byte 1 will be zero on a 4-axis controller.
    def bytesToBits(bytes: Array[Byte], count: Int): Array[Boolean] =
      (0 until count).map { i =>
        val byteIdx = i / 8
        val bitIdx  = i % 8
        if byteIdx < bytes.length then ((bytes(byteIdx) >> bitIdx) & 1) != 0
        else false
      }.toArray

    val updates = Map(
      "digitalInputs"        -> bytesToBits(generalState.inputs,  16),
      "digitalOutputs"       -> bytesToBits(generalState.outputs, 16),
      "threadStatus"         -> threadStatusByte,
      "lastPollingTime"      -> Instant.ofEpochMilli(scanTimeMs),
      "currentPollingRateHz" -> pollingRateHz
    )

    internalState ! InternalStateActor.UpdateHcdState(updates, context.system.ignoreRef)

  /**
   * Update axis state from GalilAxisStatus.
   * Sends operational state (position, velocity, switches) to AxisState
   * and command-relevant state (moving, stopCode) to AxisCmdState.
   *
   * Returns the position value it published, in encoder counts -- the stepper/servo
   * branch below is the single definition of "position" for an axis, and the ADR-002
   * history recorder consumes this return rather than recomputing it.
   */
  private def updateAxisState(axisChar: Char, axisStatus: GalilAxisStatus): Double =
    // Map axis character to Axis enum
    val axis = Axis.fromChar(axisChar)
    
    // Parse both the status word and switches byte from QR DataRecord
    val status = parseAxisStatus(axisStatus.status)
    val switches = parseSwitches(axisStatus.switches)
    
    // Stepper mode is reported by the controller in switches byte bit 0.
    // For stepper motors (no encoder), position comes from auxiliaryPosition (TD / step count).
    // For servo motors, position comes from motorPosition (TP / encoder count).
    val isStepper = switches.stepperMode
    val position = if isStepper then
      axisStatus.auxiliaryPosition.toDouble
    else
      axisStatus.motorPosition.toDouble
    
    // Velocity in QR DataRecord is 64x the TV command value (per Galil docs)
    val velocity = axisStatus.velocity.toDouble / 64.0
    
    // Build operational state update (position, velocity, named switches)
    val axisUpdates = Map(
      "position" -> position,
      "velocity" -> velocity,
      "positionError" -> axisStatus.positionError.toDouble,
      "forwardLimit" -> switches.forwardLimit,
      "reverseLimit" -> switches.reverseLimit,
      "homeSwitch" -> switches.homeInput,
      "isStepper" -> isStepper,
      "negativeDirection" -> status.negativeDirection,
      "motorOff" -> status.motorOff
    )
    
    // Send operational state update
    internalState ! InternalStateActor.UpdateAxisState(axis, axisUpdates, context.system.ignoreRef)
    
    // Build command state update.
    // moving: bit 15 of status word ("Move in Progress"); reliable for ALL motor types.
    // activeThread is NOT set here; IS owns the thread→axis registry and updates
    // activeThread via RegisterThread (set) and ScanObservations (clear).
    // inPosition is mirrored automatically by InternalStateActor from AxisState.
    val cmdUpdates = Map[String, Any](
      "moving"   -> status.moveInProgress,
      "stopCode" -> (axisStatus.stopCode & 0xFF)  // unsigned byte
    )

    internalState ! InternalStateActor.UpdateAxisCmdState(axis, cmdUpdates, context.system.ignoreRef)

    // Yield the published position for the ADR-002 history recorder (see scaladoc).
    position

  /**
   * Parsed status data from Galil QR DataRecord axis status WORD (2 bytes).
   * Per DMC-41x3 User Manual, Data Record section:
   *
   *   Bit 15: Move in Progress
   *   Bit 14: Mode of Motion PA or PR
   *   Bit 13: Mode of Motion PA only
   *   Bit 12: Find Edge (FE) in Progress
   *   Bit 11: Home (HM) in Progress
   *   Bit 10: 1st Phase of HM complete
   *   Bit  9: 2nd Phase of HM complete (or FI command issued)
   *   Bit  8: Mode of Motion Coord. Motion
   *   Bit  7: Negative Direction Move
   *   Bit  6: Mode of Motion Contour
   *   Bit  5: Motion is slewing
   *   Bit  4: Motion is stopping due to ST or Limit Switch
   *   Bit  3: Motion is making final decel
   *   Bit  2: Latch is armed
   *   Bit  1: 3rd Phase of HM in Progress
   *   Bit  0: Motor Off
   */
  private case class AxisStatusData(
    moveInProgress: Boolean,       // bit 15 — THE reliable moving flag
    motionModePA_PR: Boolean,      // bit 14
    motionModePAonly: Boolean,     // bit 13
    findEdgeInProgress: Boolean,   // bit 12
    homeInProgress: Boolean,       // bit 11
    hmPhase1Complete: Boolean,     // bit 10
    hmPhase2Complete: Boolean,     // bit  9
    coordMotion: Boolean,          // bit  8
    negativeDirection: Boolean,    // bit  7
    contourMode: Boolean,          // bit  6
    slewing: Boolean,              // bit  5
    stopping: Boolean,             // bit  4
    finalDecel: Boolean,           // bit  3
    latchArmed: Boolean,           // bit  2
    hmPhase3InProgress: Boolean,   // bit  1
    motorOff: Boolean              // bit  0
  )
  
  /**
   * Parse axis status word from QR DataRecord
   */
  private def parseAxisStatus(statusWord: Short): AxisStatusData =
    val s = statusWord & 0xFFFF  // unsigned
    AxisStatusData(
      moveInProgress = (s & (1 << 15)) != 0,
      motionModePA_PR = (s & (1 << 14)) != 0,
      motionModePAonly = (s & (1 << 13)) != 0,
      findEdgeInProgress = (s & (1 << 12)) != 0,
      homeInProgress = (s & (1 << 11)) != 0,
      hmPhase1Complete = (s & (1 << 10)) != 0,
      hmPhase2Complete = (s & (1 << 9)) != 0,
      coordMotion = (s & (1 << 8)) != 0,
      negativeDirection = (s & (1 << 7)) != 0,
      contourMode = (s & (1 << 6)) != 0,
      slewing = (s & (1 << 5)) != 0,
      stopping = (s & (1 << 4)) != 0,
      finalDecel = (s & (1 << 3)) != 0,
      latchArmed = (s & (1 << 2)) != 0,
      hmPhase3InProgress = (s & (1 << 1)) != 0,
      motorOff = (s & (1 << 0)) != 0
    )
  
  /**
   * Parsed data from Galil QR DataRecord axis switches BYTE.
   *
   * Per DMC-500x0 / DMC-4080 User Manual, Data Record section:
   *
   *   Bit 7: Latch Occurred
   *   Bit 6: State of Latch Input
   *   Bit 5: N/A
   *   Bit 4: N/A
   *   Bit 3: Forward Limit switch INACTIVE (1 = OK to move +, 0 = limit hit)
   *   Bit 2: Reverse Limit switch INACTIVE (1 = OK to move -, 0 = limit hit)
   *   Bit 1: State of Home Input
   *   Bit 0: Stepper Mode
   *
   * The forward/reverse limit bits are inverted at parse time so the field
   * names match their meaning (forwardLimit=true means "limit hit"), matching
   * how the rest of the codebase (HMI, axisError reporting) uses them.
   *
   * NOTE: These are RAW I/O states for the limit bits; the CN configuration
   * determines which physical input level (high/low) constitutes "active",
   * but the manual-documented semantic of the BIT is "INACTIVE", which is
   * what we invert.
   *
   * The TS command has its own different bit layout; do not conflate.
   */
  private case class SwitchData(
    latchOccurred: Boolean,     // bit 7
    latchInput: Boolean,        // bit 6
    forwardLimit: Boolean,      // bit 3 inverted: true = forward limit HIT
    reverseLimit: Boolean,      // bit 2 inverted: true = reverse limit HIT
    homeInput: Boolean,         // bit 1
    stepperMode: Boolean        // bit 0
  )
  
  /**
   * Parse switches byte from QR DataRecord. Forward/reverse limit bits are
   * INVERTED so the field names match their meaning (true = limit hit).
   */
  private def parseSwitches(switchByte: Byte): SwitchData =
    SwitchData(
      latchOccurred = (switchByte & (1 << 7)) != 0,
      latchInput = (switchByte & (1 << 6)) != 0,
      forwardLimit = (switchByte & (1 << 3)) == 0,  // invert: bit clear = limit hit
      reverseLimit = (switchByte & (1 << 2)) == 0,  // invert: bit clear = limit hit
      homeInput = (switchByte & (1 << 1)) != 0,
      stepperMode = (switchByte & (1 << 0)) != 0
    )
  
  /**
   * Calculate polling period from rate
   */
  private def pollingPeriod: FiniteDuration =
    (1000.0 / pollingRateHz).toInt.milliseconds
  
  /**
   * Start periodic polling timer
   */
  private def startPolling(): Unit =
    timers.startTimerWithFixedDelay(PollController, pollingPeriod)
  
  /**
   * Stop periodic polling timer
   */
  private def stopPolling(): Unit =
    timers.cancel(PollController)
  override def onSignal: PartialFunction[org.apache.pekko.actor.typed.Signal, Behavior[Command]] = {
    case org.apache.pekko.actor.typed.PostStop =>
      log.info("ControllerStatusActor stopping — closing status connection")
      try statusIo.close() catch { case _: Exception => () }
      this
  }