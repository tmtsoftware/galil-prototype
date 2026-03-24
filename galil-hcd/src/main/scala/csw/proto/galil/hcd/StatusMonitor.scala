package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import csw.logging.client.scaladsl.LoggerFactory
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState}
import org.apache.pekko.actor.typed.scaladsl.AskPattern
import org.apache.pekko.util.Timeout

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * StatusMonitor Actor (SDD Section 4.6.3)
 * 
 * Responsibilities:
 * - Periodically poll controller with QR command
 * - Parse DataRecord response
 * - Update InternalStateActor with current positions, velocities, switches, etc.
 * - Handle errors and maintain polling even on failures
 * 
 * Integration:
 * - Requests QR from ControllerInterfaceActor
 * - Updates state via InternalStateActor
 * - Runs at configurable rate (default: 10Hz / 100ms)
 */
object StatusMonitor:
  
  // Protocol
  sealed trait Command
  
  /**
   * Periodic polling trigger (internal timer message)
   */
  private case object PollController extends Command

  /**
   * Periodic analog input polling trigger (1Hz, independent of QR rate)
   * Package-private for test access.
   */
  private[hcd] case object PollAnalogInputs extends Command
  
  /**
   * Response from ControllerInterface with QR data
   */
  case class QRResponse(dataRecord: DataRecord) extends Command
  
  /**
   * Error from ControllerInterface
   */
  case class QRError(error: String) extends Command
  
  /**
   * Command to pause QR polling (for file operations - UL/DL)
   * CRITICAL: Must be called before file operations to prevent buffer corruption
   */
  case object PauseQRPolling extends Command
  
  /**
   * Command to resume QR polling (after file operations)
   */
  case object ResumeQRPolling extends Command
  
  /**
   * Command to start/stop polling (deprecated - use Pause/Resume instead)
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
   * Internal: axis state changed notification from InternalStateActor.
   * Used to detect when axes transition between active/standby states
   * and adjust polling rate accordingly.
   */
  private[hcd] case class AxisStateChanged(stateChanged: InternalStateActor.StateChanged) extends Command
  
  /**
   * Response to GetPollingStatus
   */
  case class PollingStatus(
    enabled: Boolean, 
    rateHz: Double, 
    lastPollTime: Option[Long], 
    errorCount: Int,
    paused: Boolean  // NEW: Indicates if paused for file operations
  )
  
  /**
   * Create StatusMonitor actor
   * 
   * @param controllerInterface Actor to request QR data from
   * @param internalState Actor to update with parsed data
   * @param standbyPollingRateHz Polling rate when all axes idle (default: 1Hz)
   * @param actionPollingRateHz Polling rate when any axis active (default: 10Hz)
   */
  def apply(
    controllerInterface: ActorRef[GalilCommandMessage],
    internalState: ActorRef[InternalStateActor.Command],
    loggerFactory: LoggerFactory,
    standbyPollingRateHz: Double = 1.0,
    actionPollingRateHz: Double = 10.0
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new StatusMonitor(context, timers, controllerInterface, internalState,
          loggerFactory, standbyPollingRateHz, actionPollingRateHz)
      }
    }

/**
 * Actor implementation
 */
class StatusMonitor(
  context: ActorContext[StatusMonitor.Command],
  timers: TimerScheduler[StatusMonitor.Command],
  controllerInterface: ActorRef[GalilCommandMessage],
  internalState: ActorRef[InternalStateActor.Command],
  loggerFactory: LoggerFactory,
  standbyPollingRateHz: Double,
  actionPollingRateHz: Double
) extends AbstractBehavior[StatusMonitor.Command](context):
  
  import StatusMonitor._

  private val log = loggerFactory.getLogger(context)

  // Active axis states that require action polling rate
  private val ActiveAxisStates: Set[AxisStateEnum] =
    Set(AxisStateEnum.Homing, AxisStateEnum.Moving, AxisStateEnum.Tracking)
  
  // Current state (mutable, but only accessed within actor)
  private var pollingEnabled: Boolean = true
  private var pollingPaused: Boolean = false  // Pause for file operations
  private var pollingRateHz: Double = standbyPollingRateHz  // Start at standby
  private var lastPollTime: Option[Long] = None
  private var errorCount: Int = 0
  
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
  
  log.info(s"Started — standby: ${standbyPollingRateHz}Hz, action: ${actionPollingRateHz}Hz")
  
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
        
      case PauseQRPolling =>
        handlePauseQRPolling()
        
      case ResumeQRPolling =>
        handleResumeQRPolling()
        
      case SetPolling(enabled) =>
        handleSetPolling(enabled)
        
      case SetPollingRate(newRateHz) =>
        handleSetPollingRate(newRateHz)
        
      case GetPollingStatus(replyTo) =>
        replyTo ! PollingStatus(pollingEnabled, pollingRateHz, lastPollTime, errorCount, pollingPaused)
        Behaviors.same
      
      case AxisStateChanged(stateChanged) =>
        handleAxisStateChanged(stateChanged)
  
  /**
   * Handle periodic poll trigger
   * 
   * CRITICAL: Checks pollingPaused flag to prevent interference with file operations
   */
  private def handlePollController(): Behavior[Command] =
    // Guard: Skip if paused for file operations (handles queued timer messages)
    if pollingPaused then
      log.debug("Skipping QR - polling is paused for file operation")
      return Behaviors.same
    
    if pollingEnabled then
      log.debug("Polling controller for QR data")
      
      // Create adapter to convert GalilCommandMessage.QRResult → StatusMonitor.QRResponse
      val adapter = context.messageAdapter[GalilCommandMessage.QRResult] {
        case GalilCommandMessage.QRResult(dr: DataRecord) => QRResponse(dr)
      }
      
      // Request QR from ControllerInterface
      controllerInterface ! GalilCommandMessage.GetQR(adapter)
    
    Behaviors.same
  
  /**
   * Handle QR response from controller
   */
  private def handleQRResponse(dataRecord: DataRecord): Behavior[Command] =
    try
      lastPollTime = Some(System.currentTimeMillis())
      errorCount = 0  // Reset error count on success
      
      log.debug(s"Received QR data, sample: ${dataRecord.generalState.sampleNumber}")
      
      val activeAxisChars = dataRecord.header.blocksPresent.filter(axis => DataRecord.axes.contains(axis))
      updateHcdState(dataRecord.generalState, activeAxisChars)
      
      // Update each active axis
      activeAxisChars
        .zip(dataRecord.axisStatuses)
        .foreach { case (axisChar, axisStatus) =>
          updateAxisState(axisChar, axisStatus)
        }
      
      Behaviors.same
    catch
      case ex: Exception =>
        log.error(s"Error processing QR response: ${ex.getMessage}")
        errorCount += 1
        Behaviors.same
  
  /**
   * Handle QR error from controller
   */
  private def handleQRError(error: String): Behavior[Command] =
    log.error(s"QR request failed: $error")
    errorCount += 1
    Behaviors.same
  
  /**
   * Pause QR polling for file operations (UL/DL)
   * 
   * CRITICAL: Must be called BEFORE file operations to prevent buffer corruption.
   * Pattern from existing ControllerInterfaceActor:
   * 1. Set pause flag (prevents new QR requests)
   * 2. Cancel timer (stops scheduling new requests)
   * 3. Caller should wait ~100ms for in-flight QR to complete
   * 4. Then safe to execute file operation
   */
  private def handlePauseQRPolling(): Behavior[Command] =
    if !pollingPaused then
      log.info("Pausing QR polling for file operation")
      pollingPaused = true
      stopPolling()
    else
      log.debug("QR polling already paused")
    Behaviors.same
  
  /**
   * Resume QR polling after file operations
   */
  private def handleResumeQRPolling(): Behavior[Command] =
    if pollingPaused then
      log.info("Resuming QR polling after file operation")
      pollingPaused = false
      if pollingEnabled then
        startPolling()
    else
      log.debug("QR polling was not paused")
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
      if pollingEnabled && !pollingPaused then
        stopPolling()
        startPolling()
      
      // Update IS with current rate
      internalState ! InternalStateActor.UpdateHcdState(
        Map("currentPollingRateHz" -> pollingRateHz),
        context.system.ignoreRef
      )
    
    Behaviors.same
  
  /**
   * Poll all 8 general-purpose analog inputs via `MG @AN[n]` (1-indexed per Galil convention).
   *
   * These are the 8 uncommitted analog inputs on the DMC-500x0 main board — distinct from
   * the per-axis analogInput field in the QR DataRecord (which is the axis-specific latch input).
   *
   * Runs at 1Hz independently of the QR polling rate. Each channel is queried individually;
   * failures are logged and that channel retains its previous value (zero at startup).
   * Results are pushed into HcdState.analogInputs[0..7] via UpdateHcdState.
   *
   * The simulator returns 2.5000 for all @AN[n] queries.
   */
  private def handlePollAnalogInputs(): Behavior[Command] =
    if pollingPaused then return Behaviors.same

    implicit val timeout: Timeout = Timeout(500.millis)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = context.system.scheduler

    val analogInputs = Array.fill(8)(0.0f)
    var anyFailure = false

    for channel <- 1 to 8 do
      val cmdString = s"MG @AN[$channel]"
      Try {
        val future = AskPattern.Askable(controllerInterface).ask[GalilCommandMessage.SendCommandResult](
          ref => GalilCommandMessage.SendCommand(cmdString, ref)
        )
        Await.result(future, timeout.duration)
      } match {
        case Success(result) if result.error.isEmpty =>
          Try(result.response.trim.toFloat) match {
            case Success(v)  => analogInputs(channel - 1) = v
            case Failure(ex) =>
              log.warn(s"AI poll: could not parse @AN[$channel] response '${result.response}': ${ex.getMessage}")
              anyFailure = true
          }
        case Success(result) =>
          log.warn(s"AI poll: @AN[$channel] error: ${result.error.getOrElse("unknown")}")
          anyFailure = true
        case Failure(ex) =>
          log.warn(s"AI poll: @AN[$channel] ask timed out: ${ex.getMessage}")
          anyFailure = true
      }

    if !anyFailure then
      log.debug(s"AI poll: ${analogInputs.zipWithIndex.map{case(v,i) => s"AN[${i+1}]=${v}V"}.mkString(", ")}")

    internalState ! InternalStateActor.UpdateHcdState(
      Map("analogInputs" -> analogInputs),
      context.system.ignoreRef
    )

    Behaviors.same

  /**
   * Update HCD-level state from GeneralState and report thread status to IS.
   * IS owns the thread→axis registry and resolves completions from the bitmask.
   */
  private def updateHcdState(generalState: GeneralState, activeAxisChars: Seq[Char]): Unit =
    val threadStatusByte = generalState.threadStatus & 0xFF

    // inputs/outputs: 10 bytes in QR; only the first byte (bits 0-7) is meaningful
    // on the DMC-500x0 main board (8 optoisolated DI, 8 optoisolated DO).
    // We expand to a 16-element Boolean array for future slave-module support:
    // bytes 0-1 → bits 0-15. Byte 1 will be zero on a 4-axis controller.
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

    // Send HCD-level update (position, I/O, timing)
    internalState ! InternalStateActor.UpdateHcdState(updates, context.system.ignoreRef)

    // Report raw thread status to IS. IS compares against its thread→axis registry
    // to detect completions and set activeThread=0 on the correct axis.
    // No axis↔thread mapping here — IS owns that knowledge.
    internalState ! InternalStateActor.UpdateThreadStatus(threadStatusByte)
  
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
    timers.cancel(PollAnalogInputs)

  override def onSignal: PartialFunction[org.apache.pekko.actor.typed.Signal, Behavior[Command]] =
    case org.apache.pekko.actor.typed.PostStop =>
      timers.cancel(PollController)
      timers.cancel(PollAnalogInputs)
      this