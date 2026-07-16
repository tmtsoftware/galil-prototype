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
  case Uninitialized, Ready, Faulted

/**
 * Connection status for a single TCP handle to the Galil DMC-500x0.
 *   Disconnected: initial state before the connect attempt, or after a detected drop.
 *   Connected:    TCP handle open and responding.
 *
 * The console connection is hardware-only and informational; its status does not
 * affect HCD operational readiness. The command and status connections are both
 * required for normal operation.
 */
enum ConnectionStatus:
  case Disconnected, Connected

/**
 * Axis operational state, published in CurrentStateAxis[A-H].axisState.
 * Transitions are command-lifecycle driven (SDD Figure 4-2).
 *
 * Transitions:
 *   startup → Lost
 *   Lost:     homeAxis → Homing
 *   Homing:   success → Idle,  stopAxis → Lost,  fault → Error
 *   Idle:     homeAxis → Homing,  motion command → Moving,  trackAxis → Tracking
 *   Moving:   success → Idle,  stopAxis → Idle,  fault → Error
 *   Tracking: stopAxis → Idle,  trackAxis → Tracking,  fault → Error
 *   Error:    homeAxis → Homing
 *             stopAxis → Idle if the axis has a valid home reference, else Lost
 *
 * Error is the latch for any fault. The recovery state out of Error via stopAxis
 * depends on whether a valid home exists, recorded by the per-axis `homed` flag.
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
      // stopAxis: valid from ANY state; it is a safety command and is also
      // required to escape Error (Error → Idle via stopAxis, SDD Figure 4-2).
      // CommandHandler executes the interruption protocol when a program is active.
      case (_, "stopAxis") => None

      // homeAxis: accepted from Lost, Idle, Error only (SDD Figure 4-2).
      // Cannot interrupt Homing or Moving; those require stopAxis first.
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
   * Determine the axis state after a stopAxis command completes, based on the
   * state the axis was in when stop was issued and whether it has a valid home
   * reference (SDD Figure 4-2).
   *   Lost     → Lost   (not homed; stop is safe but changes nothing)
   *   Homing   → Lost   (homing interrupted; axis position unknown)
   *   Moving   → Idle   (homed; position is known)
   *   Tracking → Idle   (homed; position is known)
   *   Error    → Idle if homed, else Lost
   *   Idle     → Idle   (already stopped)
   *
   * @param homed true iff the axis has a valid home reference.
   */
  def stopCompletionState(homed: Boolean): AxisStateEnum =
    this match
      case Lost    => Lost                         // remains Lost; only homeAxis escapes
      case Homing  => Lost                         // homing interrupted; axis position unknown
      case Error   => if homed then Idle else Lost // disambiguate by home status
      case _       => Idle                         // Moving, Tracking, Idle → all transition to Idle

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
 * Published via CSW CurrentStateAxis[A-H] by the CurrentStatePublisherActor, and
 * updated at the QR polling rate by the ControllerStatusActor.
 *
 * @param axisState Current operational state (command-lifecycle driven)
 * @param axisError Error message if in error state
 * @param homed True iff the axis has a valid home reference. Cleared at the start of every
 *   homeAxis attempt, set to true only on homeAxis success. Used by stopCompletionState to
 *   determine the post-stop state out of Error (Idle if a valid home exists, else Lost).
 *   Published in CurrentStateAxis so assemblies can derive their indexed status from HCD truth.
 * @param position Raw accumulated motor position in encoder counts, as reported by the
 *   DMC-500x0. For rotating axes this accumulates across revolutions (e.g. 800 after two
 *   full revolutions of a 400-count axis). Used unchanged for all internal math (inPosition,
 *   applyApproachAlgorithm, distance/timeout). See motorPosition for the wrapped 0..cpr value.
 * @param velocity Current motor velocity (counts/sec)
 * @param positionError Current position error
 * @param demand Requested motor target in accumulated encoder counts (same space as position).
 *   Set by CommandHandlerActor to the algorithm-adjusted absolute count after applyApproachAlgorithm.
 *   Internal only, not published. inPosition is |position - demand| <= inPositionThreshold.
 * @param inPositionThreshold Threshold for calculating inPosition
 * @param inPosition Whether the axis is within threshold of demand (calculated)
 * @param forwardLimit Forward limit switch hit (true = limit active, prevents +motion).
 *   Derived from QR switches byte bit 3, which the Galil reports in the inactive sense;
 *   parseSwitches inverts the bit so the field name matches its meaning.
 * @param reverseLimit Reverse limit switch hit (true = limit active, prevents -motion).
 *   Derived from QR switches byte bit 2, same inversion as forwardLimit.
 * @param homeSwitch Home switch active (QR switches bit 1)
 * @param isStepper True if stepper motor type (QR switches bit 0)
 * @param negativeDirection True if moving in negative direction (axis status word bit 7)
 * @param motorOff True if motor amplifier is off / not energized (axis status word bit 0)
 * @param forwardLimitEnabled True if the forward limit is enabled per the controller _LD config.
 *   Read once at init from MG _LDx (bit 0 of LD = forward disabled). Defaults to true.
 * @param reverseLimitEnabled True if the reverse limit is enabled per the controller _LD config.
 *   Read once at init (bit 1 of LD = reverse disabled). Defaults to true.
 * @param mechanismType Type of mechanism (linear or rotating)
 * @param upperLimit Upper soft limit in encoder counts (linear mechanisms only). Seeded from
 *   AxisConfig at init, in the same units as positionAxis.target and position. Soft-limit
 *   enforcement is active only when upperLimit > lowerLimit (a degenerate 0.0/0.0 disables
 *   enforcement) and softLimitsEnabled is true.
 * @param lowerLimit Lower soft limit in encoder counts (linear mechanisms only).
 * @param algorithm Target approach algorithm (for rotating mechanisms)
 * @param softLimitsEnabled Per-axis runtime bypass for soft-limit enforcement (linear axes only).
 *   When true (default), positionAxis and offsetAxis targets outside [lowerLimit, upperLimit]
 *   are rejected at validate-time. When false, the limits are not consulted, leaving the axis
 *   protected only by its hardware limit switches (#LIMSWI), which is the condition required to
 *   test those switches. Operator-controlled via the HMI, not exposed in the assembly ICD, and
 *   internal only. Has no effect on rotating axes or on homeAxis.
 *
 * Motion configuration (mirrors the embedded variables in SDD Table 3-2, set by configAxis,
 * used for timeout calculation):
 * @param maxSpeed Configured max speed for motion programs (counts/sec)
 * @param acceleration Acceleration for motion programs (counts/sec²)
 * @param deceleration Deceleration for motion programs (counts/sec²)
 * @param indexOffset Home offset applied after finding the home switch (encoder counts)
 * @param indexSpeed Homing speed for accurate detection of home switches (counts/sec)
 * @param motionDelay Settling time after motion before reporting done (ms)
 * @param countsPerRevolution Encoder counts per revolution (rotating axes only), read from the
 *   controller cpd[] array after #Init. Used by applyApproachAlgorithm to resolve the target for
 *   positionAxis/offsetAxis, and to compute angularPosition for publication.
 */
case class AxisState(
  axisState: AxisStateEnum = AxisStateEnum.Lost,
  axisError: String = "",
  /** True iff the axis has a valid home reference. Cleared at the start of every homeAxis
    * attempt; set to true only when homeAxis completes successfully. Used by
    * stopCompletionState for the post-stop state out of Error (Idle if a valid home exists,
    * else Lost). Published in CurrentStateAxis so assemblies can derive their indexed status
    * from HCD truth. */
  homed: Boolean = false,
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
  // Limit-switch enabled status, read once at init from controller _LDx (LD = Limit
  // Disable). Decoded bits: bit 0 = forward disabled, bit 1 = reverse disabled.
  // Defaults to true (enabled).
  forwardLimitEnabled: Boolean = true,
  reverseLimitEnabled: Boolean = true,
  // Mechanism configuration
  mechanismType: MechanismType = MechanismType.Linear,
  upperLimit: Option[Double] = None,
  lowerLimit: Option[Double] = None,
  algorithm: Option[RotatingAlgorithm] = None,
  // Per-axis runtime bypass for soft-limit enforcement.  Defaults to true (limits active)
  // so the safe-by-default behaviour applies on startup and after any state reset.  Toggled
  // off temporarily from the HMI when the operator wants to test hardware limit switches.
  // Has no effect on rotating axes (no soft limits configured) or on homeAxis.
  softLimitsEnabled: Boolean = true,
  // Motion configuration (embedded variables, seeded from config at init / configAxis override, SDD Table 3-2)
  maxSpeed: Option[Double] = None,
  acceleration: Option[Double] = None,
  deceleration: Option[Double] = None,
  indexOffset: Option[Double] = None,
  indexSpeed: Option[Double] = None,
  motionDelay: Option[Double] = None,
  countsPerRevolution: Option[Double] = None,  // rotating axes only; read from embedded cpd[]
  axisName: Option[String] = None,             // human-readable mechanism name from config
  // Achieved wheel slot (1-based), reported by the controller's wheel-select logic
  // (sensor-decoded, gated on the detent for pupil-mask wheels). -1 = unknown: no
  // successful select since startup/home, or not a wheel axis (the embedded leaves
  // those -1). Polled from embedded whlpos[] by ControllerStatusActor and published
  // in CurrentStateAxis.wheelPosition.
  wheelPosition: Int = -1,
  // Commanded wheel slot (1-based) for an in-effect selectWheel; -1 = no select governs
  // (so inPosition reverts to the encoder-angle test). Set by handleSelectWheel and
  // cleared automatically when any command transitions the axis to Moving/Homing without
  // re-asserting it. While set, inPosition is driven by the embedded #Select program's
  // declared success (wheelPosition == this slot), not the achieved angle — only the
  // embedded can confirm the detent is engaged and the position sensors agree.
  commandedWheelPosition: Int = -1,
  /**
   * Live PVT tracking-session ledger.  Some(_) iff axisState == Tracking; None otherwise.
   * Created on the first trackAxis arriving in Idle (StartTracking → axisState Tracking).
   * Updated on each subsequent trackAxis (ContinueTracking).  Cleared on stopAxis, fault,
   * homeAxis, or MarkUnderrun.  See `TrackingSession` for field semantics.
   *
   * Invariant: axisState == Tracking ⇔ trackingSession.isDefined.  Any code path that
   * transitions axisState into or out of Tracking is responsible for keeping this in sync.
   */
  trackingSession: Option[TrackingSession] = None,
  /**
   * Live readings from the Galil PVT monitoring path, populated by the InternalStateActor
   * from ReportPvtMonitoring messages the ControllerStatusActor sends during action-rate
   * polling while the axis is Tracking. Held at default values (`pvFreeSlots = 255`,
   * `btSegmentsExecuted = 0`) outside a tracking session, since the controller resets
   * `_BT<x>` to 0 on each BT and an empty FIFO reports 255 free slots.
   *
   * Surfaced to the HMI tracking telemetry panel; not part of the CSW ICD.
   *
   *   pvFreeSlots         _PV<x>: free segment slots in the controller's PVT buffer,
   *                       range 0..255 (capacity 255; the active segment is not counted).
   *   btSegmentsExecuted  _BT<x>: PVT segments the controller has executed since the most
   *                       recent BT<x>. Compared with trackingSession.segmentsSubmitted,
   *                       the difference measures FIFO depth.
   */
  pvFreeSlots: Int = 255,
  btSegmentsExecuted: Int = 0
):
  /**
   * Calculate inPosition based on position, demand, and threshold.
   */
  def calculateInPosition: Boolean =
    if commandedWheelPosition >= 1 then
      // A selectWheel governs this axis: trust the embedded #Select program's declared
      // success — it sets whlpos (==> wheelPosition) to the commanded slot only after the
      // detent is engaged and the position sensors confirm it. The encoder angle alone
      // cannot establish that, so we compare slots, not counts. wheelPosition == -1
      // (unknown / mid-move) correctly yields false until the embedded confirms arrival.
      wheelPosition == commandedWheelPosition
    else
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
   * This matches the demand space (0..cpr) used by positionAxis and offsetAxis, and is
   * what the user or Assembly perceives as the current position.
   * Example: position 800 on a 400-count axis gives motorPosition 0 (two full wraps).
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
   * Validate a target position against this axis's soft limits.
   *
   * Returns None when the target is acceptable (or when soft-limit enforcement does
   * not apply to this axis), and Some(reason) when the target must be rejected.
   *
   * Enforcement applies only when ALL of:
   *   - mechanismType is Linear (rotating axes have no soft limits)
   *   - softLimitsEnabled is true (operator hasn't bypassed for limit-switch testing)
   *   - upperLimit and lowerLimit are both set, with upperLimit > lowerLimit
   *     (degenerate 0.0/0.0 from an unconfigured linear axis is treated as "limits
   *     not configured" and disables enforcement, matching the sentinel pattern used
   *     for countsPerRevolution=0.0 elsewhere)
   *
   * Targets are compared in raw encoder counts (the same space as `position` and
   * positionAxis.target).
   *
   * @param target the proposed absolute target position in encoder counts
   * @return None if the target is acceptable, Some(reason) if it violates a soft limit
   */
  def checkSoftLimit(target: Double): Option[String] =
    if mechanismType != MechanismType.Linear then None
    else if !softLimitsEnabled then None
    else (lowerLimit, upperLimit) match
      case (Some(lo), Some(hi)) if hi > lo =>
        if target > hi then
          Some(f"target $target%.0f exceeds upper soft limit $hi%.0f")
        else if target < lo then
          Some(f"target $target%.0f below lower soft limit $lo%.0f")
        else None
      case _ => None  // limits not configured; enforcement disabled

  /**
   * Update this axis state with new values.
   * Uses a map of field names to values.
   */
  def update(updates: Map[String, Any]): AxisState =
    var updated = this
    
    updates.foreach {
      case ("axisState", v: AxisStateEnum) => updated = updated.copy(axisState = v)
      case ("axisError", v: String) => updated = updated.copy(axisError = v)
      case ("homed", v: Boolean) => updated = updated.copy(homed = v)
      case ("position", v: Double) => updated = updated.copy(position = v)
      case ("velocity", v: Double) => updated = updated.copy(velocity = v)
      case ("positionError", v: Double) => updated = updated.copy(positionError = v)
      case ("demand", v: Double) => updated = updated.copy(demand = v)
      case ("inPositionThreshold", v: Double) => updated = updated.copy(inPositionThreshold = v)
      case ("inPosition", v: Boolean) => updated = updated.copy(inPosition = v)
      case ("wheelPosition", v: Int) => updated = updated.copy(wheelPosition = v)
      case ("commandedWheelPosition", v: Int) => updated = updated.copy(commandedWheelPosition = v)
      // Named switch fields
      case ("forwardLimit", v: Boolean) => updated = updated.copy(forwardLimit = v)
      case ("reverseLimit", v: Boolean) => updated = updated.copy(reverseLimit = v)
      case ("forwardLimitEnabled", v: Boolean) => updated = updated.copy(forwardLimitEnabled = v)
      case ("reverseLimitEnabled", v: Boolean) => updated = updated.copy(reverseLimitEnabled = v)
      case ("homeSwitch", v: Boolean) => updated = updated.copy(homeSwitch = v)
      case ("isStepper", v: Boolean) => updated = updated.copy(isStepper = v)
      case ("negativeDirection", v: Boolean) => updated = updated.copy(negativeDirection = v)
      case ("motorOff", v: Boolean) => updated = updated.copy(motorOff = v)
      // Mechanism configuration
      case ("mechanismType", v: MechanismType) => updated = updated.copy(mechanismType = v)
      case ("upperLimit", v: Double) => updated = updated.copy(upperLimit = Some(v))
      case ("lowerLimit", v: Double) => updated = updated.copy(lowerLimit = Some(v))
      case ("algorithm", v: RotatingAlgorithm) => updated = updated.copy(algorithm = Some(v))
      case ("softLimitsEnabled", v: Boolean) => updated = updated.copy(softLimitsEnabled = v)
      // Motion configuration (from configAxis / controller query)
      case ("maxSpeed", v: Double) => updated = updated.copy(maxSpeed = Some(v))
      case ("acceleration", v: Double) => updated = updated.copy(acceleration = Some(v))
      case ("deceleration", v: Double) => updated = updated.copy(deceleration = Some(v))
      case ("indexOffset", v: Double) => updated = updated.copy(indexOffset = Some(v))
      case ("indexSpeed", v: Double) => updated = updated.copy(indexSpeed = Some(v))
      case ("motionDelay", v: Double) => updated = updated.copy(motionDelay = Some(v))
      case ("countsPerRevolution", v: Double) =>
        // Only store nonzero values; 0.0 means "not configured" (linear axis or
        // uninitialized simulator). None is the clearer sentinel.
        if v > 0.0 then updated = updated.copy(countsPerRevolution = Some(v))
      case ("axisName", v: String) =>
        if v.nonEmpty then updated = updated.copy(axisName = Some(v))
      case ("axisName", v: Option[?]) =>
        updated = updated.copy(axisName = v.asInstanceOf[Option[String]])
      // Tracking-session ledger.  Some(TrackingSession) sets/replaces the session;
      // None clears it.  Whoever sets axisState = Tracking is responsible for also
      // setting trackingSession in the same update map; whoever sets axisState to
      // anything else is responsible for clearing it.
      case ("trackingSession", v: TrackingSession) =>
        updated = updated.copy(trackingSession = Some(v))
      case ("trackingSession", None) =>
        updated = updated.copy(trackingSession = None)
      case ("trackingSession", v: Option[?]) =>
        updated = updated.copy(trackingSession = v.asInstanceOf[Option[TrackingSession]])
      // PVT monitoring readings, written by InternalStateActor.handleReportPvtMonitoring
      // when the axis is Tracking. Outside Tracking, defaults apply (255 free / 0 executed).
      case ("pvFreeSlots", v: Int) =>
        updated = updated.copy(pvFreeSlots = v)
      case ("btSegmentsExecuted", v: Int) =>
        updated = updated.copy(btSegmentsExecuted = v)
      case (key, value) => 
        // Log unknown keys but don't fail
        println(s"Warning: Unknown axis state field: $key = $value")
    }
    
    // Drop the wheel-select commitment when a command starts a new motion that isn't a
    // select. Any command transitioning the axis to Moving/Homing without re-asserting
    // commandedWheelPosition in the same update (selectWheel is the sole exception, and it
    // sets it) means the axis is no longer committed to a selected slot, so inPosition must
    // revert to the encoder-angle test. Safe to key on the transition: axisState is set to
    // Moving/Homing only by command handlers, never by the status/QR path, so this cannot
    // misfire mid-select.
    val startsNonSelectMotion =
      updates.get("axisState").exists {
        case AxisStateEnum.Moving | AxisStateEnum.Homing => true
        case _                                           => false
      } && !updates.contains("commandedWheelPosition")
    if startsNonSelectMotion then
      updated = updated.copy(commandedWheelPosition = -1)

    // Recalculate inPosition whenever an input to calculateInPosition changed: the
    // angle-test inputs (position/demand/threshold), or the slot-test inputs
    // (wheelPosition from the embedded poll, commandedWheelPosition on select), or the
    // commitment having just been cleared above (mode switch back to the angle test).
    if (updates.contains("position") || updates.contains("demand") || updates.contains("inPositionThreshold")
        || updates.contains("wheelPosition") || updates.contains("commandedWheelPosition")
        || startsNonSelectMotion)
      updated.copy(inPosition = updated.calculateInPosition)
    else
      updated

// ========================================
// Per-Axis Tracking Session State
// ========================================

/**
 * Per-axis runtime state for an active PVT tracking session.
 *
 * Lifecycle:
 *   - Created by InternalStateActor on the first `trackAxis` command arriving on an
 *     axis in `axisState = Idle` (StartTracking message).  AxisState transitions to
 *     Tracking simultaneously.
 *   - Updated on each subsequent `trackAxis` command (ContinueTracking message):
 *     `lastTargetCounts` and `lastValidTime` advance, `segmentsSubmitted` increments.
 *   - Cleared by `stopAxis` (→ Idle), `EnterFaulted` (→ Error/Lost), `homeAxis`
 *     (→ Homing), and `MarkUnderrun` (→ Error).  Holding axisState = Tracking with
 *     no TrackingSession is an invariant violation.
 *
 * The session is the HCD-side ledger used to compute ΔP and T_samples for each outgoing
 * PVA write from the absolute targets the Assembly supplies. The Assembly cannot keep
 * this state itself, since its notion of "last sent" can diverge from what the HCD
 * actually queued (HCD rejection, controller fault, mid-stream restart). The HCD is the
 * only layer that knows both what the Assembly asked for and what the controller has.
 *
 * @param lastTargetCounts Encoder counts of the last segment endpoint successfully
 *   written to the controller's PVT FIFO. For rotating axes this is in raw motor counts
 *   (unwrapped, accumulating across revolutions); the Assembly supplies degrees and the
 *   HCD converts via countsPerRevolution. For linear axes this is the absolute target
 *   counts (passthrough). Used as the previous endpoint for the next segment's ΔP.
 *
 * @param lastValidTime TAI instant at which the last submitted segment is supposed
 *   to end (the validTime carried on the trackAxis that placed it in the FIFO).
 *   Used for (a) computing T_samples = (newValidTime - lastValidTime) × samples/sec
 *   for the next segment, and (b) underrun pre-detection: when TAI now exceeds
 *   lastValidTime and no new segment has arrived, the controller would subsequently
 *   underrun silently, so the InternalStateActor transitions the axis to Error proactively.
 *
 * @param btFiredAt TAI instant when BT<axis> was sent to start trajectory execution.
 *   Diagnostic only, for correlating HCD log timing with the controller _BT<x>
 *   segment-completion counter.
 *
 * @param segmentsSubmitted Monotonic count of PVA segments accepted into the FIFO during
 *   this session. Diagnostic only, for cross-checking against _BT<x> and for log forensics
 *   if a session ends unexpectedly. Resets to zero on session start (it counts within a
 *   single BT epoch, parallel to how _BT<x> resets on each new BT).
 */
case class TrackingSession(
  lastTargetCounts: Long,
  lastValidTime: Instant,
  btFiredAt: Instant,
  segmentsSubmitted: Long
)

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
 * @param activeThread Thread number executing command on this axis; -1 if none.
 * @param axisErrorMsg Error message from active command (from ae[] on controller)
 * @param inPosition Whether axis has reached target position (mirrored from AxisState)
 * @param moving Whether axis motor is physically in motion (from QR switches bit 4)
 * @param activeCommand Currently active command type (internal)
 * @param commandHalted Flag set when command is interrupted (internal)
 * @param stopCode Raw stop code from DataRecord (internal, 0=moving, 1=decel, 2=fwd limit, etc.)
 */
case class AxisCmdState(
  // Published in CommandStateAxis[A-H]
  activeThread: Int = -1,
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
      case ("clearActiveCommand", _: Boolean) => updated = updated.copy(activeCommand = None, activeThread = -1)
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
 * @param controllerId Controller instance id (from controller.id config; 0..N)
 * @param controllerErrorMsg Controller error message
 * @param version Embedded version number
 * @param controllerAxisCount Axis count reported by the controller ID command (4 or 8); -1 if unknown.
 *   Parsed from the firmware model string (DMC-500x0 gives x axes). Determines the number of
 *   DI/DO channels intrinsic to the controller model (DMC-50040: 8 DI, 8 DO; DMC-50080: 16 DI,
 *   16 DO), used by the HMI to determine which I/O bits are active.
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
  state: HcdStateEnum = HcdStateEnum.Uninitialized,
  controllerId: Int = 1,
  controllerErrorMsg: String = "",
  // Free-form reason for the current Uninitialized state, populated by GalilHcd
  // at startup ("startup") and by handleFaultReset during recovery ("faultReset
  // Init", etc), and cleared when the HCD transitions to Ready. The HMI uses it
  // to render a descriptive banner while the HCD is Uninitialized. Internal only,
  // not published over CSW.
  initializingReason: String = "",
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
  cmdStates: Map[Axis, AxisCmdState] = Map.empty,
  /**
   * Controller servo-loop sample period in microseconds, read from the `_TM` operand
   * during HCD initialization.  The default Galil servo loop runs at 1 kHz (TM=1000
   * µs/sample); both lab DMC-50040 and STB DMC-4080 are configured this way and the
   * PVT design assumes this value, but it can be reconfigured per controller, so it is
   * read rather than hardcoded.
   *
   * Used by handleTrackAxis to convert a delta in TAI time into the integer T_samples
   * argument of PVA<x>=ΔP,V,T:
   *     T_samples = round((newValidTime - prevValidTime) × 1e6 / controllerSamplePeriodMicros)
   *
   * Default 0 means "not yet read"; handleTrackAxis checks for this and completes Invalid
   * (the HCD has not finished initializing) rather than dividing by zero.
   */
  controllerSamplePeriodMicros: Int = 0
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
      case ("initializingReason", v: String) => updated = updated.copy(initializingReason = v)
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
      case ("controllerSamplePeriodMicros", v: Int) => updated = updated.copy(controllerSamplePeriodMicros = v)
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