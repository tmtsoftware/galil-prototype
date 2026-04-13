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
   * @param standbyPollingRateHz Polling rate when all axes idle (default: 1Hz)
   * @param actionPollingRateHz  Polling rate when any axis active (default: 10Hz)
   */
  def apply(
    galilConfig: GalilConfig,
    internalState: ActorRef[InternalStateActor.Command],
    loggerFactory: LoggerFactory,
    standbyPollingRateHz: Double = 1.0,
    actionPollingRateHz: Double = 10.0
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val statusIo: GalilIo = GalilIoTcp(galilConfig.host, galilConfig.port)
        new ControllerStatusActor(context, timers, statusIo, internalState,
          loggerFactory, standbyPollingRateHz, actionPollingRateHz)
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
    actionPollingRateHz: Double = 10.0
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new ControllerStatusActor(context, timers, statusIo, internalState,
          loggerFactory, standbyPollingRateHz, actionPollingRateHz)
      }
    }

/**
 * Actor implementation
 */
class ControllerStatusActor(
  context: ActorContext[ControllerStatusActor.Command],
  timers: TimerScheduler[ControllerStatusActor.Command],
  statusIo: GalilIo,
  internalState: ActorRef[InternalStateActor.Command],
  loggerFactory: LoggerFactory,
  standbyPollingRateHz: Double,
  actionPollingRateHz: Double
) extends AbstractBehavior[ControllerStatusActor.Command](context):
  
  import ControllerStatusActor._

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
      
      case AxisStateChanged(stateChanged) =>
        handleAxisStateChanged(stateChanged)
  
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
          Behaviors.same
        case ex: Exception =>
          log.error(s"QR poll failed (non-IO): ${ex.getMessage}")
          errorCount += 1
          Behaviors.same
    else
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
   * Update HCD-level state from GeneralState and report thread status to IS.
   * IS owns the thread→axis registry and resolves completions from the bitmask.
   *
   * If the QR errorCode is nonzero, the controller has a pending error. We call
   * TC 1 on the status connection immediately to retrieve and clear it, then set
   * HcdState to Faulted with a descriptive controllerErrorMsg. This is a passive
   * read from QR followed by a single TC 1 — no interaction with the command
   * connection or actor mailbox.
   *
   * TC 1 clears the error latch on the controller, so a subsequent QR poll will
   * show errorCode=0. The Faulted state remains until the HCD is restarted.
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

    // Send HCD-level update (position, I/O, timing)
    internalState ! InternalStateActor.UpdateHcdState(updates, context.system.ignoreRef)

    // Report raw thread status to IS. IS compares against its thread→axis registry
    // to detect completions and set activeThread=0 on the correct axis.
    // No axis↔thread mapping here — IS owns that knowledge.
    internalState ! InternalStateActor.UpdateThreadStatus(threadStatusByte)

    // Check for controller error. errorCode is a latch: nonzero means the controller
    // recorded an error since the last TC call. Retrieve and clear it with TC 1,
    // then fault the HCD so operators are forced to investigate.
    // Skip if already Faulted — errors may repeat every poll cycle from a running
    // embedded error handler; we only need to capture the first one.
    val rawErrorCode = generalState.errorCode & 0xFF
    if rawErrorCode != 0 && !controllerFaulted then
      fetchAndReportControllerError(rawErrorCode)

  /**
   * Retrieve the controller error description via TC 1 and set HcdState to Faulted.
   *
   * Called when QR errorCode is nonzero. TC 1 returns a string of the form
   * "N Description\r\n" (e.g. "66 Array space full") and clears the latch.
   * We format it as "Controller Error: N Description" and push to IS.
   *
   * TC is called on statusIo (the status TCP connection) — same connection used
   * for QR polling. This is safe: we are inside the synchronous QR poll path,
   * so no interleaving can occur.
   */
  private def fetchAndReportControllerError(rawErrorCode: Int): Unit =
    val errorMsg = Try {
      val responses = statusIo.send("TC 1")
      val tcText = responses.head._2.utf8String.trim
      // TC 1 returns "N Description" — use as-is since it already contains the code.
      // If TC somehow returns empty or just "0" (error already cleared by the time
      // we called it), fall back to the raw code from QR.
      if tcText.isEmpty || tcText == "0" then
        s"Controller Error: $rawErrorCode (description unavailable)"
      else
        s"Controller Error: $tcText"
    }.getOrElse(s"Controller Error: $rawErrorCode (TC call failed)")

    log.error(errorMsg)
    controllerFaulted = true
    internalState ! InternalStateActor.UpdateHcdState(
      Map(
        "state"              -> HcdStateEnum.Faulted,
        "controllerErrorMsg" -> errorMsg
      ),
      context.system.ignoreRef
    )
  
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