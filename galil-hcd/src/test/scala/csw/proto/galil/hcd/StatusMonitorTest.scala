package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState}

import java.time.Instant
import scala.concurrent.duration._

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
    standbyPollingRateHz: Double = 1.0,
    actionPollingRateHz: Double = 10.0
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new StatusMonitor(context, timers, controllerInterface, internalState,
          standbyPollingRateHz, actionPollingRateHz)
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
  standbyPollingRateHz: Double,
  actionPollingRateHz: Double
) extends AbstractBehavior[StatusMonitor.Command](context):
  
  import StatusMonitor._
  
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
  
  context.log.info(s"StatusMonitor started - standby: ${standbyPollingRateHz}Hz, action: ${actionPollingRateHz}Hz")
  
  override def onMessage(msg: Command): Behavior[Command] =
    msg match
      case PollController =>
        handlePollController()
        
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
      context.log.debug("Skipping QR - polling is paused for file operation")
      return Behaviors.same
    
    if pollingEnabled then
      context.log.debug("Polling controller for QR data")
      
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
      
      context.log.debug(s"Received QR data, sample: ${dataRecord.generalState.sampleNumber}")
      
      // Update HCD-level state (including thread status → per-axis activeThread)
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
        context.log.error(s"Error processing QR response: ${ex.getMessage}", ex)
        errorCount += 1
        Behaviors.same
  
  /**
   * Handle QR error from controller
   */
  private def handleQRError(error: String): Behavior[Command] =
    context.log.error(s"QR request failed: $error")
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
      context.log.info("Pausing QR polling for file operation")
      pollingPaused = true
      stopPolling()
    else
      context.log.debug("QR polling already paused")
    Behaviors.same
  
  /**
   * Resume QR polling after file operations
   */
  private def handleResumeQRPolling(): Behavior[Command] =
    if pollingPaused then
      context.log.info("Resuming QR polling after file operation")
      pollingPaused = false
      if pollingEnabled then
        startPolling()
    else
      context.log.debug("QR polling was not paused")
    Behaviors.same
  
  /**
   * Enable/disable polling
   */
  private def handleSetPolling(enabled: Boolean): Behavior[Command] =
    if enabled != pollingEnabled then
      pollingEnabled = enabled
      if enabled then
        context.log.debug("Polling enabled")
        startPolling()
      else
        context.log.debug("Polling disabled")
        stopPolling()
    Behaviors.same
  
  /**
   * Change polling rate
   */
  private def handleSetPollingRate(newRateHz: Double): Behavior[Command] =
    if newRateHz > 0 && newRateHz != pollingRateHz then
      pollingRateHz = newRateHz
      context.log.info(s"Polling rate changed to ${pollingRateHz}Hz (${pollingPeriod.toMillis}ms)")
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
      context.log.info(s"Polling rate → ${pollingRateHz}Hz ($reason)")
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
   * Update HCD-level state from GeneralState.
   * Also decodes threadStatus bitmask and updates per-axis activeThread in AxisCmdState.
   */
  private def updateHcdState(generalState: GeneralState, activeAxisChars: Seq[Char]): Unit =
    val threadStatusByte = generalState.threadStatus & 0xFF
    
    val updates = Map(
      "digitalInputs"       -> generalState.inputs.map(_ != 0),
      "digitalOutputs"      -> generalState.outputs.map(_ != 0),
      "threadStatus"        -> threadStatusByte,
      "lastPollingTime"     -> Instant.ofEpochMilli(System.currentTimeMillis()),
      // Include current rate on every poll so the HMI always reflects the live
      // rate without a separate message. Avoids the race where a fast move
      // completes before the rate-change UpdateHcdState reaches subscribers.
      "currentPollingRateHz" -> pollingRateHz
    )
    
    // Send HCD-level update
    internalState ! InternalStateActor.UpdateHcdState(updates, context.system.ignoreRef)
    
    // threadStatus (HcdState) is the controller-wide active-thread bitmask from QR _NO.
    // CommandWatcher uses AxisCmdState.activeThread (the specific thread allocated by CI
    // for the current command) and checks it against threadStatus to detect completion.
    // SM must NOT write AxisCmdState.activeThread — that is owned by CH (set at program
    // start) and cleared by CommandWatcher via clearActiveCommand on completion.
  
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
    
    // Build command state update
    // moving: bit 15 of status word ("Move in Progress") — reliable for ALL motor types
    // Note: inPosition is mirrored automatically by InternalStateActor
    val cmdUpdates = Map[String, Any](
      "moving" -> status.moveInProgress,
      "stopCode" -> (axisStatus.stopCode & 0xFF)  // unsigned byte
    )
    
    // Send command state update
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
   * Stop polling timer
   */
  private def stopPolling(): Unit =
    timers.cancel(PollController)