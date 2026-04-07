package csw.proto.galil.hcd

import scala.concurrent.duration._

/**
 * GalilMotion HCD Configuration
 * 
 * Based on SDD Figure 4-3 and Section 4.4
 * 
 * This configuration is loaded from the CSW Configuration Service
 * and contains controller connection parameters and axis settings.
 */
case class GalilHcdConfig(
  controller: ControllerConfig,
  simulate: Boolean,
  activeAxes: Seq[Boolean],  // 8 elements for axes A-H
  axes: Map[String, AxisConfig]
)

/**
 * Controller connection configuration
 * 
 * @param host IP address as array of 4 integers (e.g., [192, 168, 1, 100])
 * @param port TCP port for Galil controller
 * @param id Instance identifier for this Galil controller (1-4)
 * @param embeddedProgram Path to DMC program file containing #Init, #Setup{A-H} programs
 *                        Relative to programs.resources directory
 *                        File contains #Version program that returns version number in _TM1
 *                        Example: "galil_embedded_v1.dmc"
 * @param standbyPollingRateHz QR polling rate when all axes are idle (Hz)
 * @param actionPollingRateHz QR polling rate when any axis is active (Hz)
 */
case class ControllerConfig(
  host: Seq[Int],
  port: Int,
  id: Int,
  embeddedProgram: String,
  standbyPollingRateHz: Double = 1.0,
  actionPollingRateHz: Double = 10.0
) {
  /** Convert host array to IP string (e.g., "192.168.1.100") */
  def hostString: String = host.mkString(".")
}

/**
 * Per-axis configuration
 * 
 * These parameters map to internal state variables and are used
 * during axis initialization and motion commands.
 * 
 * @param mechanismType "linear" or "rotating"
 * @param upperLimit Upper soft limit (mm or deg). Required for linear; optional for rotating
 *   (defaults to 0.0, which disables soft limit enforcement).
 * @param lowerLimit Lower soft limit (mm or deg). Required for linear; optional for rotating
 *   (defaults to 0.0).
 * @param algorithm Approach algorithm: "forward", "reverse", or "shortest".
 *   Required for rotating; optional for linear (defaults to "forward", the only meaningful
 *   value for a linear mechanism).
 * @param inPositionThreshold Threshold for in-position status
 * @param indexOffset Offset applied after homing
 *
 * Motion parameters — written to the controller's embedded variables at HCD init
 * (writeMotionConfig(), Tier 2 authority). These values supplant the embedded
 * EEPROM defaults (#SetupX) and remain effective until overridden by an Assembly
 * via configAxis (Tier 3).
 *
 * Three-tier authority:
 *   Tier 1: Embedded EEPROM defaults  — standalone Galil Tools use only
 *   Tier 2: HCD config file (here)    — written at HCD init
 *   Tier 3: Assembly configAxis       — runtime session override
 *
 * @param maxSpeed     Maximum move speed (counts/sec)
 * @param acceleration Acceleration rate (counts/sec²)
 * @param deceleration Deceleration rate (counts/sec²)
 * @param motionDelay  Post-move settling time (ms)
 * @param indexSpeed   Homing speed (counts/sec)
 * @param countsPerRevolution Encoder counts for one full 360° revolution (rotating axes only).
 *   Integer value — e.g. 400 for a 400-step/rev stepper, 3600 for simulator.
 *   Written to cpr[] on the controller at init. 0.0 = not configured (warn for rotating).
 */
case class AxisConfig(
  mechanismType: String,       // "linear" or "rotating"
  upperLimit: Double,          // mm or degrees (required for linear; optional for rotating)
  lowerLimit: Double,          // mm or degrees (required for linear; optional for rotating)
  algorithm: String,           // "forward", "reverse", or "shortest" (required for rotating; optional for linear)
  inPositionThreshold: Double, // Positional tolerance
  indexOffset: Double,         // Offset after homing
  axisName: Option[String] = None, // Human-readable mechanism name (e.g. "ANT Linear")
  // Motion parameters (optional — hardware values overwritten from controller at init)
  maxSpeed: Double     = 1000.0,  // counts/sec
  acceleration: Double = 9216.0,  // counts/sec²
  deceleration: Double = 9216.0,  // counts/sec²
  motionDelay: Double  = 100.0,   // ms (post-move settling)
  indexSpeed: Double   = 256.0,   // counts/sec (homing speed)
  countsPerRevolution: Double = 0.0   // counts/rev (rotating only; 0.0 = not configured)
)

object GalilHcdConfig {
  
  /**
   * Parse configuration from Typesafe Config
   * 
   * Expected format (from SDD Figure 4-3):
   * {{{
   * controller {
   *   host = [192, 168, 1, 100]
   *   port = 12345
   *   id = 1
   *   embeddedVersion = 1
   * }
   * 
   * simulate = false
   * activeAxes = [true, true, false, false, false, false, false, false]
   * 
   * axes {
   *   A { ... }
   *   B { ... }
   * }
   * }}}
   */
  def fromConfig(config: com.typesafe.config.Config): GalilHcdConfig = {
    import scala.jdk.CollectionConverters._
    
    // Parse controller config
    val controllerConfig = config.getConfig("controller")
    val controller = ControllerConfig(
      host = controllerConfig.getIntList("host").asScala.map(_.toInt).toSeq,
      port = controllerConfig.getInt("port"),
      id = controllerConfig.getInt("id"),
      embeddedProgram = controllerConfig.getString("embeddedProgram"),
      standbyPollingRateHz = if controllerConfig.hasPath("standbyPollingRateHz")
        then controllerConfig.getDouble("standbyPollingRateHz") else 1.0,
      actionPollingRateHz = if controllerConfig.hasPath("actionPollingRateHz")
        then controllerConfig.getDouble("actionPollingRateHz") else 10.0
    )
    
    // Parse simulate flag
    val simulate = config.getBoolean("simulate")
    
    // Parse active axes
    val activeAxes = config.getBooleanList("activeAxes").asScala.map(_.booleanValue()).toSeq
    
    // Validate activeAxes has exactly 8 elements
    require(activeAxes.length == 8, s"activeAxes must have exactly 8 elements (A-H), got ${activeAxes.length}")
    
    // Parse axes configurations
    val axesConfig = config.getConfig("axes")
    val axes = Seq("A", "B", "C", "D", "E", "F", "G", "H")
      .flatMap { axis =>
        if (axesConfig.hasPath(axis)) {
          val axisConf = axesConfig.getConfig(axis)
          def optDouble(key: String, default: Double): Double =
            if axisConf.hasPath(key) then axisConf.getDouble(key) else default
          val mechType = axisConf.getString("mechanismType")
          val isLinear = mechType == "linear"
          Some(axis -> AxisConfig(
            mechanismType       = mechType,
            // limits: required for linear; optional for rotating (default 0.0 = no soft limit)
            upperLimit          = optDouble("upperLimit", 0.0),
            lowerLimit          = optDouble("lowerLimit", 0.0),
            // algorithm: required for rotating; optional for linear (only "forward" is meaningful)
            algorithm           = if axisConf.hasPath("algorithm") then axisConf.getString("algorithm")
                                  else if isLinear then "forward"
                                  else throw com.typesafe.config.ConfigException.Missing(
                                    s"axes.$axis.algorithm is required for rotating mechanisms"),
            inPositionThreshold = axisConf.getDouble("inPositionThreshold"),
            indexOffset         = axisConf.getDouble("indexOffset"),
            axisName            = if axisConf.hasPath("name") then Some(axisConf.getString("name")) else None,
            // Motion params — optional, fall back to case class defaults
            maxSpeed            = optDouble("maxSpeed",     1000.0),
            acceleration        = optDouble("acceleration", 9216.0),
            deceleration        = optDouble("deceleration", 9216.0),
            motionDelay         = optDouble("motionDelay",  100.0),
            indexSpeed          = optDouble("indexSpeed",   256.0),
            countsPerRevolution     = optDouble("countsPerRevolution", 0.0)
          ))
        } else {
          None
        }
      }
      .toMap
    
    GalilHcdConfig(controller, simulate, activeAxes, axes)
  }
  
  /**
   * Default configuration for testing with 2-motor controller
   * Matches the user's physical test controller (axes A and B)
   */
  def defaultTestConfig: GalilHcdConfig = GalilHcdConfig(
    controller = ControllerConfig(
      host = Seq(127, 0, 0, 1),  // localhost for testing
      port = 8888,
      id = 1,
      embeddedProgram = "galil_embedded_v1.dmc"
    ),
    simulate = true,  // Default to simulation mode for testing
    activeAxes = Seq(true, true, false, false, false, false, false, false),
    axes = Map(
      "A" -> AxisConfig(
        mechanismType = "linear",
        upperLimit = 1000.0,
        lowerLimit = 0.0,
        algorithm = "forward",
        inPositionThreshold = 5.0,
        indexOffset = 10.0
      ),
      "B" -> AxisConfig(
        mechanismType = "rotating",
        upperLimit = 360.0,
        lowerLimit = 0.0,
        algorithm = "shortest",
        inPositionThreshold = 1.0,
        indexOffset = 0.0,
        countsPerRevolution = 3600.0   // 360° × 10 counts/° — simulator
      )
    )
  )
}