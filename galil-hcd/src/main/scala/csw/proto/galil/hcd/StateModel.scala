package csw.proto.galil.hcd

import java.time.Instant

/**
 * State model for the GalilMotion HCD Internal State Actor.
 * 
 * This represents the complete internal state as defined in SDD Section 4.5.
 * All state is immutable and thread-safe.
 *
 * The state is split into two per-axis structures:
 *   - AxisState: Operational state published via CurrentStateAxis[A-H] (position, velocity, etc.)
 *   - AxisCmdState: Command execution state for CommandStateAxis[A-H] and CommandWatcher use
 *
 * This separation ensures that high-frequency position/velocity updates (every QR poll)
 * don't trigger unnecessary CommandWatcher evaluations. CommandWatchers subscribe only
 * to AxisCmdState changes, which occur far less frequently.
 */

// ========================================
// Enums
// ========================================

enum HcdStateEnum:
  case Ready, Faulted

/**
 * Connection status for a single TCP handle to the Galil DMC-500x0.
 *
 * Disconnected — initial state before connect attempt, or after a detected drop.
 * Connected    — TCP handle open and responding.
 *
 * Note: The console connection is hardware-only and informational; its status
 * does not affect HCD operational readiness. Command and status connections
 * are both required for normal operation.
 */
enum ConnectionStatus:
  case Disconnected, Connected

/**
 * Axis operational state (SDD Figure 4-2).
 * Published in CurrentStateAxis[A-H].axisState.
 * Transitions are command-lifecycle driven.
 *
 * State machine (Figure 4-2):
 *   startup → Lost
 *   Lost:     homeAxis → Homing
 *   Homing:   success → Idle,  stopAxis → Lost,  fault → Error
 *   Idle:     homeAxis → Homing,  motionCmd → Moving,  trackAxis → Tracking
 *   Moving:   success → Idle,  stopAxis → Idle,  fault → Error
 *   Tracking: stopAxis → Idle,  trackAxis → Tracking,  fault → Error
 *   Error:    homeAxis → Homing
 */
enum AxisStateEnum:
  case Lost, Homing, Idle, Moving, Tracking, Error

  /**
   * Check if the given command is accepted in this axis state (SDD Figure 4-2).
   * Returns None if accepted, or Some(reason) if rejected.
   *
   * Interruption-eligible combinations (SDD 4.8.1) return None here so the
   * CommandHandler can execute the full interruption protocol. The CommandHandler
   * is responsible for the actual interruption logic; this method only gates out
   * truly invalid state transitions.
   */
  def validateCommand(commandName: String): Option[String] =
    import AxisStateEnum._
    (this, commandName) match
      // stopAxis: valid from ANY state — it is a safety command.
      // Also required to escape Error state (Error → Idle via stopAxis, SDD Figure 4-2).
      // CommandHandler executes interruption protocol when a program is active.
      case (_, "stopAxis") => None

      // homeAxis: accepted from Lost, Idle, Error only (SDD Figure 4-2).
      // Cannot interrupt Homing or Moving — those require stopAxis first.
      case (Lost,  "homeAxis") => None
      case (Idle,  "homeAxis") => None
      case (Error, "homeAxis") => None

      // positionAxis, offsetAxis, selectWheel, positionWheel: from Idle, or Moving via interruption
      case (Idle,   "positionAxis")   => None
      case (Moving, "positionAxis")   => None  // CommandHandler will interrupt
      case (Idle,   "offsetAxis")     => None
      case (Moving, "offsetAxis")     => None  // CommandHandler will interrupt
      case (Idle,   "selectWheel")    => None
      case (Moving, "selectWheel")    => None  // CommandHandler will interrupt
      case (Idle,   "positionWheel")  => None
      case (Moving, "positionWheel")  => None  // CommandHandler will interrupt

      // trackAxis: from Idle or Tracking (re-issue updates velocity/target)
      case (Idle,     "trackAxis") => None
      case (Tracking, "trackAxis") => None

      // Reject everything else with a descriptive message
      case (state, cmd) =>
        Some(s"$cmd command not valid in $state state")

  /**
   * Determine the axis state after a stopAxis command completes,
   * based on the state the axis was in when stop was issued.
   *
   * Per SDD Figure 4-2:
   *   Lost     → stopAxis → Lost     (still not homed; stop is safe but changes nothing)
   *   Homing   → stopAxis → Lost     (homing interrupted; axis position unknown)
   *   Moving   → stopAxis → Idle     (was homed; position is known)
   *   Tracking → stopAxis → Idle     (was homed; position is known)
   *   Error    → stopAxis → Idle     (stop clears the fault condition)
   *   Idle     → stopAxis → Idle     (no-op; already stopped)
   */
  def stopCompletionState: AxisStateEnum =
    this match
      case Lost    => Lost   // stopAxis on Lost axis — remains Lost; only homeAxis escapes
      case Homing  => Lost   // homing interrupted — axis position unknown
      case _       => Idle   // Moving, Tracking, Error, Idle → all transition to Idle

enum MechanismType:
  case Linear, Rotating

enum RotatingAlgorithm:
  case Forward, Reverse, Shortest

enum ActiveCommand:
  case Home, Move, Track, Select, Stop

enum Axis:
  case A, B, C, D, E, F, G, H
  
  def index: Int = this.ordinal
  def char: Char = this.toString.head

object Axis:
  /**
   * Convert character to Axis enum
   */
  def fromChar(c: Char): Axis = c.toUpper match
    case 'A' => Axis.A
    case 'B' => Axis.B
    case 'C' => Axis.C
    case 'D' => Axis.D
    case 'E' => Axis.E
    case 'F' => Axis.F
    case 'G' => Axis.G
    case 'H' => Axis.H
    case _ => throw new IllegalArgumentException(s"Invalid axis: $c")

// ========================================
// Per-Axis Operational State (CurrentStateAxis)
// ========================================

/**
 * Operational state for a single axis (A-H).
 * Published via CSW CurrentStateAxis[A-H] by the CurrentStatePublisherActor.
 * Updated at QR polling rate by StatusMonitor.
 * 
 * @param axisState Current operational state (command-lifecycle driven)
 * @param axisError Error message if in error state
 * @param position Raw accumulated motor position in encoder counts, as reported directly by the
 *   DMC-500x0. For rotating axes this value accumulates across revolutions (e.g. 800 after two
 *   full revolutions of a 400-count axis). Used unchanged for all internal math: inPosition,
 *   applyApproachAlgorithm, distance/timeout calculations. Also called "rawMotorPosition" in
 *   comments — this is the authoritative source of truth for where the motor physically is.
 *   See motorPosition for the wrapped 0..cpr display value.
 * @param velocity Current motor velocity (counts/sec)
 * @param positionError Current position error
 * @param demand Requested motor target in accumulated encoder counts (matches position space).
 *   Set by CommandHandlerActor to the algorithm-adjusted absolute count after applyApproachAlgorithm.
 *   Internal only — not published in CurrentStateAxis. inPosition is calculated as
 *   |position - demand| <= inPositionThreshold, both in accumulated-count space.
 * @param inPositionThreshold Threshold for calculating inPosition
 * @param inPosition Whether axis is within threshold of demand (calculated)
 * @param forwardLimit Forward limit switch active (QR switches bit 0)
 * @param reverseLimit Reverse limit switch active (QR switches bit 1)
 * @param homeSwitch Home switch active (QR switches bit 2)
 * @param isStepper True if stepper motor type (QR switches bit 3)
 * @param negativeDirection True if moving in negative direction (QR switches bit 5)
 * @param motorOff True if motor amplifier is off / not energized (QR switches bit 6)
 * @param mechanismType Type of mechanism (linear or rotating)
 * @param upperLimit Upper soft limit (for linear mechanisms)
 * @param lowerLimit Lower soft limit (for linear mechanisms)
 * @param algorithm Target approach algorithm (for rotating mechanisms)
 *
 * Motion configuration (mirrors embedded variables from SDD Table 3-2,
 * set by configAxis command, used for timeout calculation):
 * @param maxSpeed Configured max speed for motion programs (counts/sec), set via configAxis velocity param
 * @param acceleration Acceleration for motion programs (counts/sec²)
 * @param deceleration Deceleration for motion programs (counts/sec²)
 * @param indexOffset Home offset applied after finding home switch (encoder counts)
 * @param indexSpeed Homing speed for accurate detection of home switches (counts/sec)
 * @param motionDelay Settling time after motion stopped before reporting done (ms)
 * @param countsPerRevolution Encoder counts per revolution of rotation (rotating axes only).
 *   Read from controller embedded cpd[] array via readMotionConfig() after #Init.
 *   Used by: (1) applyApproachAlgorithm in CommandHandlerActor to resolve the
 *   algorithm-adjusted count target for positionAxis/offsetAxis; (2) computing
 *   angularPosition (0-360°) for publication. Not used for linear axes.
 */
case class AxisState(
  axisState: AxisStateEnum = AxisStateEnum.Lost,
  axisError: String = "",
  position: Double = 0.0,
  velocity: Double = 0.0,
  positionError: Double = 0.0,
  demand: Double = 0.0,
  inPositionThreshold: Double = 0.001,
  inPosition: Boolean = false,
  // Named switch fields (from QR switches byte, SDD Table 4-2)
  forwardLimit: Boolean = false,
  reverseLimit: Boolean = false,
  homeSwitch: Boolean = false,
  isStepper: Boolean = false,
  negativeDirection: Boolean = false,
  motorOff: Boolean = true,  // Default: motor amplifier off
  // Mechanism configuration
  mechanismType: MechanismType = MechanismType.Linear,
  upperLimit: Option[Double] = None,
  lowerLimit: Option[Double] = None,
  algorithm: Option[RotatingAlgorithm] = None,
  // Motion configuration (embedded variables, set by configAxis / readMotionConfig, SDD Table 3-2)
  maxSpeed: Option[Double] = None,
  acceleration: Option[Double] = None,
  deceleration: Option[Double] = None,
  indexOffset: Option[Double] = None,
  indexSpeed: Option[Double] = None,
  motionDelay: Option[Double] = None,
  countsPerRevolution: Option[Double] = None  // rotating axes only; read from embedded cpd[]
):
  /**
   * Calculate inPosition based on position, demand, and threshold.
   */
  def calculateInPosition: Boolean = 
    Math.abs(position - demand) <= inPositionThreshold

  /**
   * Angular position in degrees [0, 360), computed from raw encoder position.
   * Only meaningful for rotating axes with countsPerRevolution set.
   * Returns None for linear axes or when cpd is not yet initialized.
   */
  def angularPosition: Option[Double] =
    countsPerRevolution.filter(_ > 0.0).map { cpr =>
      val raw = (position / cpr * 360.0) % 360.0
      if raw < 0.0 then raw + 360.0 else raw
    }

  /**
   * Wrapped motor position in encoder counts, for HMI display and CSW publication.
   *
   * For rotating axes: position modulo countsPerRevolution, in range [0, cpr).
   * This is what the user or Assembly perceives as "current position" — it matches
   * the demand space (0..cpr) used by positionAxis / offsetAxis commands.
   * Example: rawMotorPosition=800 on a 400-count axis → motorPosition=0 (two full wraps).
   *
   * For linear axes: identical to position (no wrapping; countsPerRevolution is not set).
   *
   * Internal math (inPosition, applyApproachAlgorithm, distance/timeout) always uses
   * the raw accumulated `position` field, not this value.
   */
  def motorPosition: Double =
    countsPerRevolution.filter(_ > 0.0).map { cpr =>
      val wrapped = position % cpr
      if wrapped < 0.0 then wrapped + cpr else wrapped
    }.getOrElse(position)

  /**
   * Wrapped demand in encoder counts, for HMI display only.
   *
   * Mirrors the wrapping logic of motorPosition so that the HMI "Demand" readout
   * is in the same [0, cpr) frame as the displayed position.  The raw accumulated
   * demand is retained in `demand` for all internal math (inPosition calculation).
   *
   * For linear axes: identical to demand.
   */
  def motorDemand: Double =
    countsPerRevolution.filter(_ > 0.0).map { cpr =>
      val wrapped = demand % cpr
      if wrapped < 0.0 then wrapped + cpr else wrapped
    }.getOrElse(demand)

  /**
   * Update this axis state with new values.
   * Uses a map of field names to values.
   */
  def update(updates: Map[String, Any]): AxisState =
    var updated = this
    
    updates.foreach {
      case ("axisState", v: AxisStateEnum) => updated = updated.copy(axisState = v)
      case ("axisError", v: String) => updated = updated.copy(axisError = v)
      case ("position", v: Double) => updated = updated.copy(position = v)
      case ("velocity", v: Double) => updated = updated.copy(velocity = v)
      case ("positionError", v: Double) => updated = updated.copy(positionError = v)
      case ("demand", v: Double) => updated = updated.copy(demand = v)
      case ("inPositionThreshold", v: Double) => updated = updated.copy(inPositionThreshold = v)
      case ("inPosition", v: Boolean) => updated = updated.copy(inPosition = v)
      // Named switch fields
      case ("forwardLimit", v: Boolean) => updated = updated.copy(forwardLimit = v)
      case ("reverseLimit", v: Boolean) => updated = updated.copy(reverseLimit = v)
      case ("homeSwitch", v: Boolean) => updated = updated.copy(homeSwitch = v)
      case ("isStepper", v: Boolean) => updated = updated.copy(isStepper = v)
      case ("negativeDirection", v: Boolean) => updated = updated.copy(negativeDirection = v)
      case ("motorOff", v: Boolean) => updated = updated.copy(motorOff = v)
      // Mechanism configuration
      case ("mechanismType", v: MechanismType) => updated = updated.copy(mechanismType = v)
      case ("upperLimit", v: Double) => updated = updated.copy(upperLimit = Some(v))
      case ("lowerLimit", v: Double) => updated = updated.copy(lowerLimit = Some(v))
      case ("algorithm", v: RotatingAlgorithm) => updated = updated.copy(algorithm = Some(v))
      // Motion configuration (from configAxis / controller query)
      case ("maxSpeed", v: Double) => updated = updated.copy(maxSpeed = Some(v))
      case ("acceleration", v: Double) => updated = updated.copy(acceleration = Some(v))
      case ("deceleration", v: Double) => updated = updated.copy(deceleration = Some(v))
      case ("indexOffset", v: Double) => updated = updated.copy(indexOffset = Some(v))
      case ("indexSpeed", v: Double) => updated = updated.copy(indexSpeed = Some(v))
      case ("motionDelay", v: Double) => updated = updated.copy(motionDelay = Some(v))
      case ("countsPerRevolution", v: Double) =>
        // Only store nonzero values — 0.0 means "not configured" (linear axis or
        // uninitialized simulator). Storing Some(0.0) would mislead callers even
        // though angularPosition already filters it; None is the clearer sentinel.
        if v > 0.0 then updated = updated.copy(countsPerRevolution = Some(v))
      case (key, value) => 
        // Log unknown keys but don't fail
        println(s"Warning: Unknown axis state field: $key = $value")
    }
    
    // Recalculate inPosition if position or demand changed
    if (updates.contains("position") || updates.contains("demand") || updates.contains("inPositionThreshold"))
      updated.copy(inPosition = updated.calculateInPosition)
    else
      updated

// ========================================
// Per-Axis Command State (CommandStateAxis)
// ========================================

/**
 * Command execution state for a single axis.
 * Maps to ICD CommandStateAxis[A-H] for publication.
 * CommandWatcher actors subscribe to changes in this structure.
 *
 * Published fields (per ICD):
 *   activeThread, axisErrorMsg, inPosition, moving
 *
 * Internal-only fields (not published, used by CommandHandler/CommandWatcher):
 *   activeCommand, commandHalted, stopCode
 *
 * @param activeThread Thread number executing command on this axis (0 if none)
 * @param axisErrorMsg Error message from active command (from ae[] on controller)
 * @param inPosition Whether axis has reached target position (mirrored from AxisState)
 * @param moving Whether axis motor is physically in motion (from QR switches bit 4)
 * @param activeCommand Currently active command type (internal)
 * @param commandHalted Flag set when command is interrupted (internal)
 * @param stopCode Raw stop code from DataRecord (internal, 0=moving, 1=decel, 2=fwd limit, etc.)
 */
case class AxisCmdState(
  // Published in CommandStateAxis[A-H]
  activeThread: Int = 0,
  axisErrorMsg: String = "",
  inPosition: Boolean = false,
  moving: Boolean = false,
  // Internal-only fields
  activeCommand: Option[ActiveCommand] = None,
  commandHalted: Boolean = false,
  stopCode: Int = 0
):
  /**
   * Update this command state with new values.
   */
  def update(updates: Map[String, Any]): AxisCmdState =
    var updated = this
    
    updates.foreach {
      case ("activeThread", v: Int) => updated = updated.copy(activeThread = v)
      case ("axisErrorMsg", v: String) => updated = updated.copy(axisErrorMsg = v)
      case ("inPosition", v: Boolean) => updated = updated.copy(inPosition = v)
      case ("moving", v: Boolean) => updated = updated.copy(moving = v)
      case ("activeCommand", v: ActiveCommand) => updated = updated.copy(activeCommand = Some(v))
      case ("clearActiveCommand", _: Boolean) => updated = updated.copy(activeCommand = None, activeThread = 0)
      case ("commandHalted", v: Boolean) => updated = updated.copy(commandHalted = v)
      case ("stopCode", v: Int) => updated = updated.copy(stopCode = v)
      case (key, value) =>
        println(s"Warning: Unknown axis cmd state field: $key = $value")
    }
    
    updated

// ========================================
// HCD-Level State
// ========================================

/**
 * Overall HCD state.
 * 
 * @param state Current HCD state
 * @param controllerId Controller number (1-4)
 * @param controllerErrorMsg Controller error message
 * @param version Embedded version number
 * @param controllerAxisCount Axis count reported by controller ID command (e.g. 4 or 8); -1 if unknown.
 *   Parsed from firmware model string: DMC500x0 → x axes. Used by HMI to determine
 *   which I/O bits are supported (8-axis controllers support slave I/O expansion, bits 9-16).
 * @param activeAxes Which axes (A-H) are configured for use
 * @param digitalInputs Current values of optoisolated inputs (16 bits)
 * @param digitalOutputs Current values of optoisolated outputs (16 bits)
 * @param analogInputs Current values of analog inputs (8 channels)
 * @param threadStatus Raw thread status bitmask from QR GeneralState (bits 0-7 = threads 0-7)
 * @param lastPollingTime Timestamp of last status monitor execution
 * @param debug Verbose logging flag
 * @param simulation Software-only simulation mode
 * @param commandConnection Status of the command TCP handle (ControllerCommandActor)
 * @param statusConnection Status of the status TCP handle (ControllerStatusActor)
 * @param consoleConnection Status of the console TCP handle (ControllerConsoleActor, hardware-only/informational)
 * @param axes Operational state for each configured axis
 * @param cmdStates Command execution state for each configured axis
 */
case class HcdState(
  state: HcdStateEnum = HcdStateEnum.Ready,
  controllerId: Int = 1,
  controllerErrorMsg: String = "",
  version: Int = 0,
  controllerAxisCount: Int = -1,
  activeAxes: Array[Boolean] = Array.fill(8)(false),
  digitalInputs: Array[Boolean] = Array.fill(16)(false),
  digitalOutputs: Array[Boolean] = Array.fill(16)(false),
  analogInputs: Array[Float] = Array.fill(8)(0.0f),
  threadStatus: Int = 0,
  lastPollingTime: Instant = Instant.EPOCH,
  standbyPollingRateHz: Double = 1.0,
  actionPollingRateHz: Double = 10.0,
  currentPollingRateHz: Double = 1.0,
  debug: Boolean = false,
  simulation: Boolean = false,
  commandConnection: ConnectionStatus = ConnectionStatus.Disconnected,
  statusConnection:  ConnectionStatus = ConnectionStatus.Disconnected,
  consoleConnection: ConnectionStatus = ConnectionStatus.Disconnected,
  axes: Map[Axis, AxisState] = Map.empty,
  cmdStates: Map[Axis, AxisCmdState] = Map.empty
):
  /**
   * True when both command and status connections are established.
   * Console connection is informational (hardware-only) and does not
   * affect operational readiness.
   */
  def isOperational: Boolean =
    commandConnection == ConnectionStatus.Connected &&
    statusConnection  == ConnectionStatus.Connected
  /**
   * Update this HCD state with new values.
   * Uses a map of field names to values.
   */
  def update(updates: Map[String, Any]): HcdState =
    var updated = this
    
    updates.foreach {
      case ("state", v: HcdStateEnum) => updated = updated.copy(state = v)
      case ("controllerId", v: Int) => updated = updated.copy(controllerId = v)
      case ("controllerErrorMsg", v: String) => updated = updated.copy(controllerErrorMsg = v)
      case ("version", v: Int) => updated = updated.copy(version = v)
      case ("controllerAxisCount", v: Int) => updated = updated.copy(controllerAxisCount = v)
      case ("activeAxes", v: Array[Boolean @unchecked]) => updated = updated.copy(activeAxes = v)
      case ("digitalInputs", v: Array[Boolean @unchecked]) => updated = updated.copy(digitalInputs = v)
      case ("digitalOutputs", v: Array[Boolean @unchecked]) => updated = updated.copy(digitalOutputs = v)
      case ("analogInputs", v: Array[Float @unchecked]) => updated = updated.copy(analogInputs = v)
      case ("threadStatus", v: Int) => updated = updated.copy(threadStatus = v)
      case ("lastPollingTime", v: Instant) => updated = updated.copy(lastPollingTime = v)
      case ("standbyPollingRateHz", v: Double) => updated = updated.copy(standbyPollingRateHz = v)
      case ("actionPollingRateHz", v: Double) => updated = updated.copy(actionPollingRateHz = v)
      case ("currentPollingRateHz", v: Double) => updated = updated.copy(currentPollingRateHz = v)
      case ("debug", v: Boolean) => updated = updated.copy(debug = v)
      case ("simulation", v: Boolean) => updated = updated.copy(simulation = v)
      case ("commandConnection", v: ConnectionStatus) => updated = updated.copy(commandConnection = v)
      case ("statusConnection",  v: ConnectionStatus) => updated = updated.copy(statusConnection = v)
      case ("consoleConnection", v: ConnectionStatus) => updated = updated.copy(consoleConnection = v)
      case (key, value) => 
        println(s"Warning: Unknown HCD state field: $key = $value")
    }
    
    updated
  
  /**
   * Update operational state for a specific axis.
   */
  def updateAxis(axis: Axis, updates: Map[String, Any]): HcdState =
    val currentAxisState = axes.getOrElse(axis, AxisState())
    val updatedAxisState = currentAxisState.update(updates)
    copy(axes = axes + (axis -> updatedAxisState))

  /**
   * Update command state for a specific axis.
   */
  def updateCmdState(axis: Axis, updates: Map[String, Any]): HcdState =
    val currentCmdState = cmdStates.getOrElse(axis, AxisCmdState())
    val updatedCmdState = currentCmdState.update(updates)
    copy(cmdStates = cmdStates + (axis -> updatedCmdState))
  
  /**
   * Get operational state for a specific axis.
   */
  def getAxis(axis: Axis): Option[AxisState] = axes.get(axis)

  /**
   * Get command state for a specific axis.
   */
  def getCmdState(axis: Axis): Option[AxisCmdState] = cmdStates.get(axis)
  
  /**
   * Initialize an axis with default state (both operational and command).
   */
  def initializeAxis(axis: Axis, mechanismType: MechanismType = MechanismType.Linear): HcdState =
    copy(
      activeAxes = activeAxes.updated(axis.index, true),
      axes = axes + (axis -> AxisState(mechanismType = mechanismType)),
      cmdStates = cmdStates + (axis -> AxisCmdState())
    )

  /**
   * Check if a specific thread is active based on threadStatus bitmask.
   * Thread N is active if bit N is set in threadStatus.
   */
  def isThreadActive(thread: Int): Boolean =
    require(thread >= 0 && thread <= 7, s"Thread must be 0-7, got $thread")
    (threadStatus & (1 << thread)) != 0