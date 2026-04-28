package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import csw.logging.client.scaladsl.LoggerFactory
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState}
import csw.proto.galil.io.{GalilIo, GalilIoTcp}

import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try
import csw.proto.galil.hcd.HcdStateEnum

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
 */
object ControllerStatusActor:
  
  // Protocol
  sealed trait Command
  
  /**
   * Periodic polling trigger (internal timer message)
   */
  private case object PollController extends Command

  /** Internal: result of the command-connection probe sent after a status IOException. */
  private case class CommandProbeResult(result: GalilCommandMessage.SendCommandResult) extends Command

  /**
   * Internal: result of the "safe all motors" command (ST;MO) sent when the HCD
   * transitions to Faulted due to an unattributable controller error. Fire-and-
   * forget from the decision logic's perspective — this handler just logs the
   * outcome. Failure here does not propagate; the HCD is already Faulted.
   */
  private case class SafeAllResult(result: GalilCommandMessage.SendCommandResult, reason: String) extends Command

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
   *   - If that succeeds the connection never actually dropped — report Connected,
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
   * Notify CS that an axis has acquired a controller thread for command execution.
   *
   * IS forwards this from CommandHandlerActor's RegisterThread call. CS uses the
   * map to interpret per-axis ae[] reads on each scan: ae[i]==1 only counts as
   * a program failure when the axis's thread is no longer running (per the
   * synthesized threadStatus byte built from _XQ<n> queries each scan).
   *
   * Sent by IS, never by external callers.
   */
  case class RegisterAxisThread(axis: Axis, thread: Int) extends Command

  /**
   * Notify CS that an axis's thread has been cleared (program ended).
   *
   * IS sends this when handleUpdateThreadStatus detects a registered thread's
   * bit transition to 0. CS removes the entry from its local axis-thread map.
   *
   * Sent by IS, never by external callers.
   */
  case class ClearAxisThread(axis: Axis) extends Command

  /**
   * Notify CS that CommandHandlerActor has halted the active thread on this
   * axis (via HX in checkAndInterrupt).
   *
   * Without this notification, the next QR scan would see "axis X registered
   * with thread N, but thread N is no longer running" and could attribute
   * residual ae[X]==1 (the entry-time flag from the program we just halted) as
   * an unexplained program failure on whatever command runs next on this axis —
   * particularly when the next command happens to reuse the same thread number.
   *
   * The handler prunes the axis from CS's axisThreads map. CH owns the
   * lifecycle: it will RegisterAxisThread again when it launches the next
   * program. The reply (NotifyAxisHaltedAck) confirms the notification has been
   * processed (synchronization point for the caller — needed so CH can be sure
   * the prune is in place before launching the next program).
   *
   * Note: ae[] values >= 2 (#POSERR/#LIMSWI/#MCTIME) are independent of program
   * execution and are detected by the regular QR scan's Step 2; no special
   * post-HX handling for them is needed here.
   */
  case class NotifyAxisHalted(axis: Axis, replyTo: ActorRef[NotifyAxisHaltedAck]) extends Command

  /**
   * Acknowledgement of NotifyAxisHalted. Carries no information — the caller
   * just needs a synchronization point confirming the notification has been
   * processed.
   */
  case class NotifyAxisHaltedAck()

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
    actionPollingRateHz: Double = 10.0
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val statusIo: GalilIo = GalilIoTcp(galilConfig.host, galilConfig.port)
        new ControllerStatusActor(context, timers, statusIo, galilConfig, internalState,
          loggerFactory, standbyPollingRateHz, actionPollingRateHz, commandActor, configuredAxes)
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
    configuredAxes: Set[Axis] = Set.empty
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // Dummy galilConfig for test — reconnect is not exercised in unit tests
        val testConfig = GalilConfig("127.0.0.1", 8888)
        // Dummy command actor for test — probe is not exercised in unit tests
        val dummyCommandActor = context.system.deadLetters.asInstanceOf[ActorRef[GalilCommandMessage]]
        new ControllerStatusActor(context, timers, statusIo, testConfig, internalState,
          loggerFactory, standbyPollingRateHz, actionPollingRateHz, dummyCommandActor, configuredAxes)
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
  configuredAxes: Set[Axis]
) extends AbstractBehavior[ControllerStatusActor.Command](context):

  import ControllerStatusActor._

  // Mutable socket reference — replaced on successful reconnect
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
  // Set true after the first controller error is detected and reported.
  // Suppresses repeat TC 1 calls on subsequent QR polls — a running embedded error
  // handler can fire CMDERR every few seconds, leaving errorCode nonzero on every poll.
  // The HCD requires restart to clear a fault; there is no recovery path.
  private var controllerFaulted: Boolean = false

  // Local axis→thread map fed by IS via RegisterAxisThread / ClearAxisThread.
  // Used to interpret per-scan ae[] reads: ae[axis]==1 only counts as a program
  // failure when axis has no live thread (per QR threadStatus). Mirrors the
  // authoritative threadRegistry in IS.
  private var axisThreads: Map[Axis, Int] = Map.empty

  // True when a previous scan saw errorCode != 0 but couldn't attribute the
  // error to any axis (no axes had both ae==1 AND a just-cleared thread).
  // The next scan re-evaluates with one more cycle of _XQ<n> updates available;
  // if still unattributable, the HCD is faulted then. This single-scan deferral
  // covers the race where errorCode latches before _XQ<n> reports -1 for the
  // thread CMDERR just halted.
  private var pendingControllerError: Boolean = false

  // Per-axis last-reported axisErrorMsg (deduplication for steady-state ae[]
  // values like POSERR/LIMSWI/MCTIME that persist across scans). Reset to "" on
  // axis state changes that imply error clearing (operator recovery via Home/Stop).
  private var lastReportedAxisError: Map[Axis, String] = Map.empty
  
  // Subscribe to IS axisState changes via message adapter
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
        
      case SetPollingRate(newRateHz) =>
        handleSetPollingRate(newRateHz)
        
      case GetPollingStatus(replyTo) =>
        replyTo ! PollingStatus(pollingEnabled, pollingRateHz, lastPollTime, errorCount)
        Behaviors.same

      case Reconnect(replyTo) =>
        handleReconnect(replyTo)

      case AxisStateChanged(stateChanged) =>
        handleAxisStateChanged(stateChanged)

      case RegisterAxisThread(axis, thread) =>
        log.debug(s"RegisterAxisThread: axis=$axis thread=$thread")
        axisThreads = axisThreads + (axis -> thread)
        Behaviors.same

      case ClearAxisThread(axis) =>
        log.debug(s"ClearAxisThread: axis=$axis")
        axisThreads = axisThreads - axis
        Behaviors.same

      case NotifyAxisHalted(axis, replyTo) =>
        // Notification from CommandHandlerActor that it has just halted the
        // active thread on this axis. Any ae[axis]==1 residue is the entry-time
        // flag from a program we deliberately stopped — not an error.
        // (ae >= 2 codes from #POSERR/#LIMSWI/#MCTIME are independent of program
        // execution and are caught by the regular QR scan's Step 2; no special
        // handling is needed here.)
        //
        // The only thing we need to do post-HX is prune this axis from
        // axisThreads, so the next QR scan's Step 3 doesn't see "axis registered
        // with a thread that just cleared" and misattribute the residue to
        // whatever command runs next on this axis. CH owns the lifecycle: it
        // will RegisterAxisThread again when it launches the next program.
        if axisThreads.contains(axis) then
          log.debug(s"NotifyAxisHalted($axis): pruning axisThreads (was ${axisThreads(axis)})")
          axisThreads = axisThreads - axis
        replyTo ! NotifyAxisHaltedAck()
        Behaviors.same

      case CommandProbeResult(result) =>
        if result.error.isDefined then
          log.error(s"Command connection probe after status loss — ALSO FAILED: ${result.error.get}. Controller is likely completely unreachable.")
        else
          log.error(s"Command connection probe after status loss — OK (response: '${result.response.trim}'). Status connection died in isolation; controller is still alive.")
        Behaviors.same

      case SafeAllResult(result, reason) =>
        if result.error.isDefined then
          log.warn(s"SafeAllMotors after fault ('$reason') — FAILED: ${result.error.get}. Motors may still be energized; controller may be unreachable.")
        else
          log.info(s"SafeAllMotors after fault ('$reason') — OK. All motion stopped, drives disabled.")
        Behaviors.same
  
  /**
   * Attempt to verify and if necessary re-establish the status TCP connection.
   *
   * Step 1 — verify existing socket: send QR and parse the DataRecord response.
   *   If this succeeds the connection was never truly lost (cable blip, OS recovery).
   *   Drain the receive buffer to discard any stale accumulated data before resuming
   *   polling, then report Connected and restart polling.
   *
   * Step 2 — if step 1 fails: close the dead socket, open a fresh GalilIoTcp,
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
    // the first post-recovery QR poll from seeing a stale errorCode and re-faulting.
    // Resets controllerFaulted so genuine future errors will be detected.
    def clearTcLatch(io: GalilIo): Unit =
      try
        val responses = io.send("TC 1")
        val tcText = responses.head._2.utf8String.trim
        if tcText.nonEmpty && tcText != "0" && !tcText.startsWith(" 0") then
          log.info(s"Reconnect: cleared controller error latch — TC 1: '$tcText' (expected after disconnect event)")
        else
          log.info("Reconnect: TC 1 — no latched controller error")
        controllerFaulted = false
      catch
        case ex: Exception =>
          log.warn(s"Reconnect: TC 1 failed: ${ex.getMessage} — controllerFaulted flag unchanged")

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

  private def handlePollController(): Behavior[Command] =
    if pollingEnabled then
      log.debug("Polling controller for QR data")
      try
        val response = statusIo.send("QR")
        val bs = response.head._2
        val dr = DataRecord(bs)
        handleQRResponse(dr)
      catch
        case ex: java.io.IOException =>
          // TCP connection lost (remote close, broken pipe, or timeout).
          // Stop polling immediately — continuing would hammer a dead socket.
          // Report Disconnected to IS so HMI and operators see the failure.
          // No reconnection here; HCD must be restarted to recover.
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
   * Per scan, in order:
   *   1. Parse QR DataRecord — captures threadStatus and errorCode at one moment.
   *   2. Compute the set of registered axes whose threads just transitioned from
   *      active to cleared since the previous scan.
   *   3. Read MG ae[i] for each configured axis (single compound read). Done after
   *      QR so that ae values reflect any errors that arose during this scan;
   *      reversing the order would race with successful program endings.
   *   4. Run the error decision logic (see decideAxisAndControllerErrors).
   *   5. Push HCD-level updates (position, I/O, timing).
   *   6. Push per-axis QR-derived updates (position, velocity, switches).
   *   7. Push UpdateThreadStatus to IS — this clears activeThread for completed
   *      threads. Must happen LAST so any axisErrorMsg from step 4 lands first
   *      and the CommandWatcher fails the command before seeing the cleared thread.
   */
  private def handleQRResponse(dataRecord: DataRecord): Behavior[Command] =
    try
      lastPollTime = Some(System.currentTimeMillis())
      errorCount = 0  // Reset error count on success

      log.debug(s"Received QR data, sample: ${dataRecord.generalState.sampleNumber}")

      val activeAxisChars = dataRecord.header.blocksPresent.filter(axis => DataRecord.axes.contains(axis))
      val rawThreadStatusByte = dataRecord.generalState.threadStatus & 0xFF
      val rawErrorCode        = dataRecord.generalState.errorCode    & 0xFF

      // Step 2: query per-thread execution state via MG _XQ<n>.
      //
      // Why not use rawThreadStatusByte (from QR)?
      //   Empirically, on this controller firmware, after CMDERR halts a thread,
      //   the thread bit in QR's threadStatus byte (and in `MG _NO`) can remain
      //   set for many seconds — until other unrelated activity settles. This
      //   is especially common when other threads are concurrently running
      //   motion. _XQ<n> is the authoritative per-thread status: -1 means
      //   stopped, regardless of what _NO claims.
      //
      // Skip the round-trip when no threads are registered. In that case
      // there's nothing to attribute against, and we synthesize threadStatus=0.
      val xqValues: Map[Int, Int] = readXqValues(axisThreads.values.toSet)

      // Synthesize a threadStatus byte from _XQ results: bit N set if _XQ<n>
      // returned a non-(-1) value (thread is executing). Threads we didn't
      // query (i.e. not in axisThreads) appear as 0 in this synthesized byte.
      // This is what we send to IS — it's the only place the byte is consumed,
      // and IS only cares about registered threads (via threadRegistry) anyway.
      // If readXqValues returned empty (e.g. parse failure) we fall back to
      // the raw QR byte to fail-closed.
      val threadStatusByte: Int =
        if axisThreads.isEmpty then 0
        else if xqValues.size != axisThreads.size then
          // Couldn't determine state for all registered threads — fall back to
          // the raw QR byte. This is the safer behavior because if the QR byte
          // says "still running" we won't fire spurious completion/error.
          log.debug(s"_XQ query returned ${xqValues.size} of ${axisThreads.size} expected; " +
            s"falling back to QR threadStatus byte 0x${rawThreadStatusByte.toHexString}")
          rawThreadStatusByte
        else
          axisThreads.values.foldLeft(0) { (acc, thread) =>
            if xqValues.getOrElse(thread, -1) != -1 then acc | (1 << thread)
            else acc
          }

      // Step 3: which registered axes have their thread no longer running?
      //
      // Criterion: axis is in our axisThreads map AND its thread bit is clear
      // in the synthesized (_XQ-derived) byte. Being in axisThreads means IS
      // told us a thread was registered; a -1 from _XQ means the program ended.
      // IS will send ClearAxisThread to prune the map on its next
      // UpdateThreadStatus, so repeated detection on later scans is naturally
      // bounded.
      val axesWithClearedThread: Set[Axis] = axisThreads.collect {
        case (axis, thread) if (threadStatusByte & (1 << thread)) == 0 =>
          axis
      }.toSet

      // Step 4: read ae[] for configured axes (graceful on parse failure).
      val aeValues: Map[Axis, Int] = readAeValues()

      // Diagnostic: when an error is latched, log the raw inputs to the
      // attribution decision so post-mortem analysis of any unexpected fault
      // is possible without instrumenting further. Cheap (only fires on
      // errorCode != 0) and at DEBUG so it doesn't clutter normal logs.
      if rawErrorCode != 0 then
        log.debug(s"QR scan with errorCode=$rawErrorCode: " +
          s"threadStatusByte=0x${threadStatusByte.toHexString} " +
          s"(raw QR=0x${rawThreadStatusByte.toHexString}, _XQ=$xqValues), " +
          s"axisThreads=$axisThreads, " +
          s"axesWithClearedThread=$axesWithClearedThread, " +
          s"aeValues=$aeValues, " +
          s"pendingControllerError=$pendingControllerError")

      // Step 5: error decision logic — may push axisErrorMsg/axisState updates.
      decideAxisAndControllerErrors(rawErrorCode, aeValues, axesWithClearedThread)

      // Steps 6–7: existing HCD-level + per-axis updates from QR
      updateHcdState(dataRecord.generalState, activeAxisChars)
      activeAxisChars
        .zip(dataRecord.axisStatuses)
        .foreach { case (axisChar, axisStatus) =>
          updateAxisState(axisChar, axisStatus)
        }

      // Step 8: thread status — IS clears activeThread for any registered
      // threads whose bits are now zero (and forwards ClearAxisThread to us).
      // We send the SYNTHESIZED byte (built from per-thread _XQ<n> queries above)
      // rather than the raw QR threadStatus byte, so IS sees accurate per-thread
      // completions even when the QR byte is stuck stale post-CMDERR.
      internalState ! InternalStateActor.UpdateThreadStatus(threadStatusByte)

      Behaviors.same
    catch
      case ex: java.io.IOException =>
        // ae[] read or other status-connection I/O failed mid-scan.
        // Same handling as a QR send failure: stop polling, report Disconnected,
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
   * Read MG ae[i] for each configured axis and return a Map[Axis, Int].
   *
   * Issued as a single compound `MG ae[0],ae[1],...` round-trip on the status
   * connection. Returns Map.empty on any parse or I/O failure — the simulator
   * does not implement ae[] storage, and we do not want to fault on its absence.
   *
   * IOExceptions propagate to handlePollController via handleQRResponse's catch
   * block (we let the QR loss handler deal with connection failures uniformly).
   */
  private def readAeValues(): Map[Axis, Int] =
    if configuredAxes.isEmpty then Map.empty
    else
      val sortedAxes = configuredAxes.toSeq.sortBy(_.index)
      val mgCmd = "MG " + sortedAxes.map(a => s"ae[${a.index}]").mkString(",")
      try
        val responses = statusIo.send(mgCmd)
        val text      = responses.map(_._2.utf8String).mkString
        val tokens    = text.trim.split("\\s+").filter(_.nonEmpty)
        if tokens.length != sortedAxes.length then
          log.debug(s"ae[] read returned ${tokens.length} tokens, expected ${sortedAxes.length}; ignoring (text='${text.trim}')")
          Map.empty
        else
          val parsed = sortedAxes.zip(tokens).flatMap { (axis, tok) =>
            Try(tok.toDouble.toInt).toOption.map(v => axis -> v)
          }.toMap
          if parsed.size != sortedAxes.length then
            log.debug(s"ae[] read produced unparseable tokens; ignoring (text='${text.trim}')")
            Map.empty
          else
            parsed
      catch
        case ex: java.io.IOException =>
          // Re-throw so the outer QR loop's IOException handler reports connection loss.
          throw new java.io.IOException(s"ae[] read failed (status connection): ${ex.getMessage}", ex)
        case ex: Exception =>
          log.debug(s"ae[] read failed (non-IO, ignored): ${ex.getMessage}")
          Map.empty

  /**
   * Read MG _XQ<n> for each currently-registered thread and return a
   * Map[thread, line], where line is the current execution line number, or -1
   * if the thread is stopped.
   *
   * Why this exists: empirically, after a CMDERR halts a thread on this
   * controller firmware, the thread bit in QR's `threadStatus` byte (and in
   * `MG _NO`) can remain set for many seconds — until other unrelated activity
   * settles. `_XQ<n>` is the authoritative per-thread status: -1 means the
   * thread has stopped, regardless of what _NO claims.
   *
   * Behavior:
   *   - If no threads are registered: skip the round-trip, return Map.empty.
   *   - On parse failure: returns Map.empty (treat as "couldn't determine"
   *     — calling code falls back to QR's threadStatus, fail-closed).
   *   - On IOException: rethrows so the outer QR loop reports connection loss.
   *
   * Sorted by thread number so the response tokens line up with the request.
   */
  private def readXqValues(threads: Set[Int]): Map[Int, Int] =
    if threads.isEmpty then Map.empty
    else
      val sortedThreads = threads.toSeq.sorted
      val mgCmd = "MG " + sortedThreads.map(t => s"_XQ$t").mkString(",")
      try
        val responses = statusIo.send(mgCmd)
        val text      = responses.map(_._2.utf8String).mkString
        val tokens    = text.trim.split("\\s+").filter(_.nonEmpty)
        if tokens.length != sortedThreads.length then
          log.debug(s"_XQ read returned ${tokens.length} tokens, expected ${sortedThreads.length}; ignoring (text='${text.trim}')")
          Map.empty
        else
          val parsed = sortedThreads.zip(tokens).flatMap { (thread, tok) =>
            Try(tok.toDouble.toInt).toOption.map(v => thread -> v)
          }.toMap
          if parsed.size != sortedThreads.length then
            log.debug(s"_XQ read produced unparseable tokens; ignoring (text='${text.trim}')")
            Map.empty
          else
            parsed
      catch
        case ex: java.io.IOException =>
          throw new java.io.IOException(s"_XQ read failed (status connection): ${ex.getMessage}", ex)
        case ex: Exception =>
          log.debug(s"_XQ read failed (non-IO, ignored): ${ex.getMessage}")
          Map.empty

  /**
   * Decide whether the controller error code (if any) and per-axis ae[] values
   * indicate a per-axis fault, an HCD-wide fault, or steady-state ae[] errors
   * (POSERR/LIMSWI/MCTIME) that occurred outside any motion command.
   *
   * Logic:
   *   1. If errorCode != 0 (and not already faulted):
   *      a. Fetch TC 1 to retrieve and clear the controller error latch.
   *      b. Find candidate axes with ae[i]==1 AND thread just cleared this scan.
   *      c. If exactly one candidate → attribute to that axis as
   *         "Embedded program error: <TC text>", set axisState=Error.
   *      d. If zero or 2+ candidates → HCD-wide Faulted (controller error not
   *         attributable to a single program failure).
   *   2. For each axis where ae[i] in {2, 3, 4}: report as per-axis error
   *      (POSERR/LIMSWI/MCTIME) with the appropriate description. Independent
   *      of errorCode — these handlers set ae[] without invoking #CMDERR.
   *   3. Edge case: ae[i]==1 AND thread just cleared AND errorCode==0 →
   *      program ended without clearing ae[] and without controller error.
   *      Should not happen with current embedded design; log warn and treat
   *      as a per-axis error to fail safe.
   *
   * Deduplication for steady-state errors: lastReportedAxisError prevents
   * repeated UpdateAxisCmdState calls when ae[] persists at the same value
   * across many scans.
   */
  private def decideAxisAndControllerErrors(
    errorCode: Int,
    aeValues: Map[Axis, Int],
    axesWithClearedThread: Set[Axis]
  ): Unit =
    // Step 1: controller error latch present
    //
    // Three-way decision in the size==0 (unattributable) case:
    //
    //   First time we see this: defer one scan. The QR snapshot returns
    //   errorCode and threadStatus from the same moment, but the controller
    //   updates these on slightly different cycles — errorCode latches the
    //   instant a command fails, while _XQ<n> may not yet report -1 for the
    //   dead thread until the next servo cycle. If we attribute too eagerly
    //   we miss the axis whose thread cleared in the very next scan. Setting
    //   the pendingControllerError flag without consuming TC lets the next
    //   scan try again with one more cycle of _XQ<n> state available.
    //
    //   Second consecutive scan still 0: genuinely unattributable —
    //   consume TC, fault HCD, safe motors.
    //
    //   The 1-candidate (clean attribution) and 2+ (multi-axis ambiguity)
    //   cases are decided immediately on the first scan; deferral wouldn't
    //   help either.
    if errorCode != 0 && !controllerFaulted then
      val programErrorCandidates = axesWithClearedThread.filter(ax =>
        aeValues.getOrElse(ax, 0) == 1
      )

      if programErrorCandidates.size == 1 then
        // Per-axis attribution — consume TC, report on the affected axis.
        val tcText = fetchTcMessage(errorCode)
        val axis = programErrorCandidates.head
        val msg  = s"Embedded program error: $tcText"
        log.warn(s"Axis $axis: program failed → $msg")
        reportAxisError(axis, msg)
        pendingControllerError = false
      else if programErrorCandidates.isEmpty && !pendingControllerError then
        // First scan with unattributable error — defer one scan.
        // Do NOT consume TC; we need the latch persistent for the retry.
        pendingControllerError = true
        log.debug(s"errorCode=$errorCode but no axis program just completed " +
          s"(axisThreads=$axisThreads, axesWithClearedThread=$axesWithClearedThread, " +
          s"aeValues=$aeValues) — deferring one scan to let _XQ<n> settle")
      else
        // Either (a) second scan still empty, or (b) 2+ candidates.
        // Both warrant HCD-wide fault. Consume TC and report.
        val tcText = fetchTcMessage(errorCode)
        val reason = if programErrorCandidates.isEmpty then
          "no axis program just completed (after one-scan defer)"
        else
          s"multiple axes just completed (${programErrorCandidates.mkString(",")})"
        log.error(s"Controller Error: $tcText ($reason) — faulting HCD")
        controllerFaulted = true
        pendingControllerError = false
        val faultMsg = s"Controller Error: $tcText"
        internalState ! InternalStateActor.EnterFaulted(faultMsg)
        // Connection is still alive (we just got a QR back and fetched TC).
        // Safe all motors: ST stops any motion, MO disables the motor drives.
        // Fire-and-forget — we're already faulted, the result is informational.
        safeAllMotors(faultMsg)
    else if errorCode == 0 && pendingControllerError then
      // The latch cleared on its own (e.g. another path consumed it). Reset
      // our deferral flag so a fresh future error starts the cycle from scratch.
      log.debug("pendingControllerError cleared (errorCode now 0)")
      pendingControllerError = false

    // Step 2: independent ae[] codes (POSERR/LIMSWI/MCTIME) on configured axes.
    // Reported regardless of errorCode — these handlers set ae[] but do not
    // generate a controller error code. Skip ae==1 here: that's program-flow
    // (handled above when the thread clears) or in-flight (ignored until clear).
    aeValues.foreach { case (axis, ae) =>
      if ae >= 2 && ae <= 4 then
        val msg = aeDescription(ae)
        if lastReportedAxisError.getOrElse(axis, "") != msg then
          log.warn(s"Axis $axis: ae=$ae → $msg")
          reportAxisError(axis, msg)
    }

    // Step 3: edge case — program ended with ae[i]==1 but no controller error.
    // Means embedded program exited (thread cleared) without clearing ae[i] and
    // without any TC error. Defensive: treat as per-axis error.
    //
    // Deduped because an axis may remain in axisThreads for a scan or two before
    // IS processes UpdateThreadStatus and sends ClearAxisThread. Skip this axis
    // entirely if ANY error has already been reported for it (step 1 may have
    // attributed the same failure one scan earlier with a richer message).
    val unexplainedAxes = axesWithClearedThread.filter { axis =>
      aeValues.getOrElse(axis, 0) == 1 && errorCode == 0 &&
        lastReportedAxisError.getOrElse(axis, "").isEmpty
    }
    unexplainedAxes.foreach { axis =>
      val msg = "Embedded program ended unexpectedly without controller error"
      log.warn(s"Axis $axis: $msg (ae=1, no errorCode)")
      reportAxisError(axis, msg)
    }

  /**
   * Send a per-axis error to IS: sets axisErrorMsg and transitions axisState
   * to Error. Called from decideAxisAndControllerErrors.
   *
   * Sent BEFORE UpdateThreadStatus in the QR scan so the watcher's
   * CmdStateChanged notification carries the error and the watcher fails the
   * command before seeing the cleared activeThread.
   */
  private def reportAxisError(axis: Axis, msg: String): Unit =
    internalState ! InternalStateActor.UpdateAxisCmdState(
      axis,
      Map("axisErrorMsg" -> msg),
      context.system.ignoreRef
    )
    internalState ! InternalStateActor.UpdateAxisState(
      axis,
      Map("axisState" -> AxisStateEnum.Error),
      context.system.ignoreRef
    )
    lastReportedAxisError = lastReportedAxisError + (axis -> msg)

  /**
   * Map an embedded ae[] code to a descriptive axis error message.
   * Codes set by #POSERR (2), #LIMSWI (3), #MCTIME (4). Code 1 (program failed)
   * is handled separately because it requires a TC fetch for context.
   */
  private def aeDescription(ae: Int): String = ae match
    case 2 => "Position error exceeded limit"
    case 3 => "Limit switch hit"
    case 4 => "Motion timeout"
    case _ => s"Embedded error code $ae"

  /**
   * Fetch and clear the controller error latch via TC 1. Returns the message
   * string (e.g. "22 Begin not possible due to limit switch"), or a fallback
   * description if the call fails or returns empty/zero.
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
   * Safe all motors on the controller by sending a compound ST;MO command via
   * the command connection.
   *
   * Called from decideAxisAndControllerErrors when an unattributable controller
   * error forces the HCD into Faulted state. Defensive: if embedded code is
   * corrupted or in an unknown state, we want motors stopped and drives
   * disabled rather than left in whatever state they happened to be in.
   *
   * Unconditional targeting:
   *   - ST (no axis arg) stops any motion on every axis. Idempotent on
   *     stationary axes.
   *   - MO (no axis arg) disables motor drives on every axis.
   *
   * Fire-and-forget from the caller's perspective. Result arrives as a
   * SafeAllResult message and is logged; failure does not escalate (the HCD is
   * already Faulted; the operator must intervene via faultReset anyway).
   *
   * Only called on the "status connection healthy, controller reachable" fault
   * path. The two other Faulted-entry paths (connection loss, faultReset
   * recovery failure) involve a dead connection — sending would IOException
   * and offer no benefit.
   */
  private def safeAllMotors(reason: String): Unit =
    val adapter = context.messageAdapter[GalilCommandMessage.SendCommandResult](
      result => SafeAllResult(result, reason)
    )
    log.info(s"SafeAllMotors: sending 'ST;MO' (reason: $reason)")
    commandActor ! GalilCommandMessage.SendCommand("ST;MO", adapter)

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

    // Clear lastReportedAxisError for any axis that has left Error state.
    // The dedupe cache should not persist past an operator recovery — without
    // this, the next occurrence of the same error on the same axis would not
    // be reported because the cached message still matches. Triggered by the
    // axisState field on any of the state-change notifications IS sends.
    hcdState.axes.foreach { case (axis, axState) =>
      if axState.axisState != AxisStateEnum.Error
         && lastReportedAxisError.getOrElse(axis, "").nonEmpty then
        log.debug(s"Clearing lastReportedAxisError($axis): axis left Error state")
        lastReportedAxisError = lastReportedAxisError - axis
    }

    val anyAxisActive = hcdState.axes.values.exists(ax => ActiveAxisStates.contains(ax.axisState))
    val targetRate = if anyAxisActive then actionPollingRateHz else standbyPollingRateHz
    
    if targetRate != pollingRateHz then
      val reason = if anyAxisActive then
        val activeAxes = hcdState.axes.collect {
          case (axis, ax) if ActiveAxisStates.contains(ax.axisState) =>
            s"${axis}:${ax.axisState}"
        }.mkString(", ")
        s"active axes [$activeAxes]"
      else
        "all axes standby"
      
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
    
    Behaviors.same
  
  private def handlePollAnalogInputs(): Behavior[Command] =
    if !pollingEnabled then return Behaviors.same
    log.debug("AI poll: sending compound MG @AN query")

    // Single compound MG command — one round-trip instead of 8 sequential calls.
    // Hardware returns space-separated values on one line: "2.5839 2.5839 0.0000 ..."
    //
    // Called directly on statusIo on the actor thread — NOT in a Future.
    // Rationale: statusIo (a plain Socket) is not thread-safe. Running the AI poll
    // in a Future on the execution context created a data race with PollController,
    // which also calls statusIo.send() on the actor thread. Since PollController
    // and PollAnalogInputs are serialized by the actor mailbox, keeping both on the
    // actor thread eliminates the race with no additional synchronization.
    val mgCmd = s"MG ${(1 to 8).map(n => s"@AN[$n]").mkString(",")}"
    try
      val responses    = statusIo.send(mgCmd)
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
    catch
      case ex: java.io.IOException =>
        // Same treatment as QR poll failure: connection is gone, stop polling.
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
   * NOTE: Thread status reporting (UpdateThreadStatus) and controller error
   * handling are now done in handleQRResponse / decideAxisAndControllerErrors
   * for proper per-axis vs HCD-wide error attribution. This method only
   * publishes the bulk per-scan status updates.
   */
  private def updateHcdState(generalState: GeneralState, activeAxisChars: Seq[Char]): Unit =
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
      "lastPollingTime"      -> Instant.ofEpochMilli(System.currentTimeMillis()),
      "currentPollingRateHz" -> pollingRateHz
    )

    internalState ! InternalStateActor.UpdateHcdState(updates, context.system.ignoreRef)

  /**
   * Update axis state from GalilAxisStatus.
   * Sends operational state (position, velocity, switches) to AxisState
   * and command-relevant state (moving, stopCode) to AxisCmdState.
   */
  private def updateAxisState(axisChar: Char, axisStatus: GalilAxisStatus): Unit =
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
    // moving: bit 15 of status word ("Move in Progress") — reliable for ALL motor types.
    // activeThread is NOT set here — IS owns the thread→axis registry and updates
    // activeThread via RegisterThread (set) and UpdateThreadStatus (clear).
    // inPosition is mirrored automatically by InternalStateActor from AxisState.
    val cmdUpdates = Map[String, Any](
      "moving"   -> status.moveInProgress,
      "stopCode" -> (axisStatus.stopCode & 0xFF)  // unsigned byte
    )

    internalState ! InternalStateActor.UpdateAxisCmdState(axis, cmdUpdates, context.system.ignoreRef)
  
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
   * Per DMC-500x0 User Manual, Data Record section:
   *
   *   Bit 7: Latch Occurred
   *   Bit 6: State of Latch Input
   *   Bit 5: N/A
   *   Bit 4: N/A
   *   Bit 3: State of Forward Limit
   *   Bit 2: State of Reverse Limit
   *   Bit 1: State of Home Input
   *   Bit 0: Stepper Mode
   *
   * NOTE: These are RAW I/O states, NOT the same as the TS command output.
   * The TS command has its own different bit layout.
   */
  private case class SwitchData(
    latchOccurred: Boolean,     // bit 7
    latchInput: Boolean,        // bit 6
    forwardLimit: Boolean,      // bit 3 — raw state (CN config determines active high/low)
    reverseLimit: Boolean,      // bit 2
    homeInput: Boolean,         // bit 1
    stepperMode: Boolean        // bit 0 — stepper mode indicator
  )
  
  /**
   * Parse switches byte from QR DataRecord
   */
  private def parseSwitches(switchByte: Byte): SwitchData =
    SwitchData(
      latchOccurred = (switchByte & (1 << 7)) != 0,
      latchInput = (switchByte & (1 << 6)) != 0,
      forwardLimit = (switchByte & (1 << 3)) != 0,
      reverseLimit = (switchByte & (1 << 2)) != 0,
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