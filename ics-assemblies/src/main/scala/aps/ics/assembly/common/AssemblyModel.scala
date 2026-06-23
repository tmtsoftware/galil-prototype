package aps.ics.assembly.common

import com.typesafe.config.Config

import scala.jdk.CollectionConverters._

/**
 * Common data model for APS-ICS stage assemblies.
 *
 * Design split (confirmed in S68 design discussion):
 *   - The HCD speaks ONLY controller units (encoder counts for linear axes; the
 *     HCD itself owns degrees<->counts for rotating axes).
 *   - Each assembly works in user units (mm for linear stages) and converts to
 *     counts via `<axis>CountsPerMm` before commanding the HCD.
 *
 * Config schema mirrors APS-ICS SDD Table 6-1 (axis common config), Table 6-2
 * (recovery retries) and per-assembly specific tables (e.g. 6-24 for the
 * Insertion Stage). Loaded from the CSW Configuration Service at initialize()
 * in production; for the prototype we load a local resource via ConfigFactory.
 */

/** Per-axis configuration (SDD Table 6-1), expressed in user units (mm). */
final case class AxisConfig(
    name: String,                 // logical axis name, e.g. "stage" (SDD axis name key)
    countsPerMm: Double,          // assembly-owned mm<->counts scale
    isRotational: Boolean,
    lowerLimitMm: Double,         // soft limits (linear only); ignored if rotational
    upperLimitMm: Double,
    rotationalMethod: Option[String], // "forward"|"reverse"|"shortest" (rotational only)
    defaultPositionMm: Double,    // moveToDefaultPosition target, relative to home zero
    galilHcd: String,             // HCD component prefix that commands this axis (e.g. aps.ICS.HCD.GalilMotion.1)
    galilChannel: String,         // Galil channel A-H
    velocity: Double,             // configAxis values, in mm-based units
    acceleration: Double,
    deceleration: Double,
    indexOffsetMm: Double,
    indexSpeed: Double,
    inPositionThresholdMm: Double,
    countsPerRevolution: Option[Int] = None // rotating axes only; degrees<->counts derives from this
):
  /** Round to the nearest integer encoder count (avoids FP drift across moves). */
  def mmToCounts(mm: Double): Double = Math.round(mm * countsPerMm).toDouble
  def countsToMm(counts: Double): Double = if countsPerMm != 0.0 then counts / countsPerMm else 0.0

  /** Counts per degree for a rotating axis, derived from countsPerRevolution.
   *  The integer counts/rev is the source of truth (preferred over a float
   *  counts/deg) to avoid accumulated rounding across revolutions. 0.0 if this
   *  is not a rotating axis (no countsPerRevolution configured). */
  def countsPerDegree: Double = countsPerRevolution.map(_ / 360.0).getOrElse(0.0)

object AxisConfig:
  /**
   * Parse one axis sub-config. `path` is the HOCON object key for the axis
   * (e.g. "stage"). Missing optional values fall back to safe defaults.
   */
  def fromConfig(path: String, c: Config): AxisConfig =
    val a = c.getConfig(path)
    def d(k: String, default: Double): Double = if a.hasPath(k) then a.getDouble(k) else default
    AxisConfig(
      name = path,
      countsPerMm = d("countsPerMm", 1.0),
      isRotational = a.hasPath("isRotational") && a.getBoolean("isRotational"),
      lowerLimitMm = if a.hasPath("softwareLimits.lower") then a.getDouble("softwareLimits.lower") else 0.0,
      upperLimitMm = if a.hasPath("softwareLimits.upper") then a.getDouble("softwareLimits.upper") else 0.0,
      rotationalMethod = if a.hasPath("rotationalPositioningMethod") then Some(a.getString("rotationalPositioningMethod")) else None,
      defaultPositionMm = d("defaultPosition", 0.0),
      galilHcd = a.getString("galilHcd").toLowerCase, // normalized for case-insensitive prefix match
      galilChannel = a.getString("galilChannel"),
      velocity = d("velocity", 0.0),
      acceleration = d("acceleration", 0.0),
      deceleration = d("deceleration", 0.0),
      indexOffsetMm = d("indexOffset", 0.0),
      indexSpeed = d("indexSpeed", 0.0),
      inPositionThresholdMm = d("inPositionThreshold", 0.0),
      countsPerRevolution = if a.hasPath("countsPerRevolution") then Some(a.getInt("countsPerRevolution")) else None
    )

// ---------------------------------------------------------------------------
// Operational / command states (SDD §6.1.3). Shared by all motion assemblies.
// ---------------------------------------------------------------------------

/** SDD §6.1.3.2. Note: the SDD prose calls the post-home state "Ready"; the
 *  published `assemblyState` enum names it OPERATIONAL. Same state, two names. */
enum OperationalState(val choice: String):
  case PreHomed    extends OperationalState("PRE_HOMED")
  case Operational extends OperationalState("OPERATIONAL") // SDD prose: "Ready"
  case Degraded    extends OperationalState("DEGRADED")
  case Faulted     extends OperationalState("FAULTED")

/** SDD §6.1.3.3. */
enum CommandState(val choice: String):
  case Idle          extends CommandState("IDLE")
  case Processing    extends CommandState("PROCESSING")
  case ErrorRecovery extends CommandState("ERROR_RECOVERY")
  case Failed        extends CommandState("FAILED")

// ---------------------------------------------------------------------------
// Snapshots of HCD CurrentState, refreshed by the subscription callback.
// Immutable case classes held behind @volatile refs (single-writer = the
// subscription thread; readers = the TLA thread). See StageAssemblyHandlers.
// ---------------------------------------------------------------------------

/** From HCD `CurrentState` (lifecycle). */
final case class HcdLifecycle(state: String, controllerErrorMsg: String):
  /** Map HCD lifecycle to the assembly `hcdState` enum choice. */
  def choice: String = state.toUpperCase match
    case "UNINITIALIZED" => "UNINITIALIZED"
    case "READY"         => "READY"
    case "FAULTED"       => "FAULTED"
    case _               => "UNINITIALIZED"

object HcdLifecycle:
  val Unknown: HcdLifecycle = HcdLifecycle("Uninitialized", "")

/** From HCD `CurrentStateAxis<x>` (one monitored axis), in controller units. */
final case class AxisSnapshot(
    positionCounts: Double,
    velocityCounts: Double,
    hcdAxisState: String,   // lost|homing|idle|moving|tracking|error
    inPosition: Boolean,
    homed: Boolean,
    axisErrorMsg: String,
    wheelPositionNum: Int = -1,       // achieved wheel slot (rotating axes); -1 = unknown / not a wheel
    angularPositionDeg: Double = 0.0  // wheel angle in degrees (rotating axes)
):
  /** Map HCD axisState -> assembly axisStatus enum (LOST/HOMING/IDLE/MOVING/ERROR).
   *  Linear stages never enter `tracking`; if seen it is treated as MOVING. */
  def assemblyAxisState: String = hcdAxisState.toLowerCase match
    case "lost"     => "LOST"
    case "homing"   => "HOMING"
    case "idle"     => "IDLE"
    case "moving"   => "MOVING"
    case "tracking" => "MOVING"
    case "error"    => "ERROR"
    case _          => "LOST"

object AxisSnapshot:
  val Unknown: AxisSnapshot = AxisSnapshot(0.0, 0.0, "lost", false, false, "")