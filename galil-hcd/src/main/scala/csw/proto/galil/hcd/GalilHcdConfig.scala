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
 * @param upperLimit Upper soft limit (mm or deg)
 * @param lowerLimit Lower soft limit (mm or deg)
 * @param algorithm Approach algorithm: "forward" or "shortest"
 * @param inPositionThreshold Threshold for in-position status
 * @param indexOffset Offset applied after homing
 *
 * Motion parameters — optional, with defaults matching the prototype hardware.
 * On hardware these are overwritten by readMotionConfig() after #Init/#SetupX run,
 * so values here serve as:
 *   1. Initial IS state before the first readMotionConfig completes
 *   2. Effective values in simulator mode (no real controller to query)
 *
 * @param maxSpeed     Maximum move speed (counts/sec)
 * @param acceleration Acceleration rate (counts/sec²)
 * @param deceleration Deceleration rate (counts/sec²)
 * @param motionDelay  Post-move settling time (ms)
 * @param indexSpeed   Homing speed (counts/sec)
 */
case class AxisConfig(
  mechanismType: String,       // "linear" or "rotating"
  upperLimit: Double,          // mm or degrees
  lowerLimit: Double,          // mm or degrees
  algorithm: String,           // "forward" or "shortest"
  inPositionThreshold: Double, // Positional tolerance
  indexOffset: Double,         // Offset after homing
  // Motion parameters (optional — hardware values overwritten from controller at init)
  maxSpeed: Double     = 1000.0,  // counts/sec
  acceleration: Double = 9216.0,  // counts/sec²
  deceleration: Double = 9216.0,  // counts/sec²
  motionDelay: Double  = 100.0,   // ms (post-move settling)
  indexSpeed: Double   = 256.0    // counts/sec (homing speed)
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
          Some(axis -> AxisConfig(
            mechanismType       = axisConf.getString("mechanismType"),
            upperLimit          = axisConf.getDouble("upperLimit"),
            lowerLimit          = axisConf.getDouble("lowerLimit"),
            algorithm           = axisConf.getString("algorithm"),
            inPositionThreshold = axisConf.getDouble("inPositionThreshold"),
            indexOffset         = axisConf.getDouble("indexOffset"),
            // Motion params — optional, fall back to case class defaults
            maxSpeed            = optDouble("maxSpeed",     1000.0),
            acceleration        = optDouble("acceleration", 9216.0),
            deceleration        = optDouble("deceleration", 9216.0),
            motionDelay         = optDouble("motionDelay",  100.0),
            indexSpeed          = optDouble("indexSpeed",   256.0)
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
        indexOffset = 0.0
      )
    )
  )
}