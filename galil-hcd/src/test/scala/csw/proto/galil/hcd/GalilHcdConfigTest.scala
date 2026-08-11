package csw.proto.galil.hcd

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

/**
 * Tests for GalilHcdConfig parsing
 * 
 * Validates configuration matches SDD Figure 4-3 format
 */
class GalilHcdConfigTest extends AnyFunSuite {
  
  test("should parse complete configuration from file") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)
    
    // Controller config
    assert(hcdConfig.controller.host == Seq(127, 0, 0, 1))
    assert(hcdConfig.controller.hostString == "127.0.0.1")
    assert(hcdConfig.controller.port == 8888)
    assert(hcdConfig.controller.id == 1)
    assert(hcdConfig.controller.embeddedProgram == "galil_embedded_v1.dmc")
    assert(hcdConfig.controller.standbyPollingRateHz == 1.0)
    assert(hcdConfig.controller.actionPollingRateHz == 10.0)
    
    // Simulation mode
    assert(hcdConfig.simulate == true)
    
    // Active axes - all 8 active (S86: default example is a fully populated
    // 8-motor controller, exercising the thread-0 last-resort case)
    assert(hcdConfig.activeAxes.length == 8)
    assert(hcdConfig.activeAxes.forall(_ == true))
  }
  
  test("should parse axis A configuration") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)
    
    val axisA = hcdConfig.axes("A")
    assert(axisA.mechanismType == "linear")
    assert(axisA.upperLimit == 1000.0)
    assert(axisA.lowerLimit == 0.0)
    assert(axisA.algorithm == "forward")
    assert(axisA.inPositionThreshold == 5.0)
    assert(axisA.indexOffset == 10.0)
  }
  
  test("should parse axis B configuration") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)
    
    val axisB = hcdConfig.axes("B")
    assert(axisB.mechanismType == "rotating")
    assert(axisB.upperLimit == 360.0)
    assert(axisB.lowerLimit == 0.0)
    assert(axisB.algorithm == "shortest")
    assert(axisB.inPositionThreshold == 1.0)
    assert(axisB.indexOffset == 0.0)
    assert(axisB.countsPerRevolution == 3600.0)
  }
  
  test("should contain configurations for all 8 active axes") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)

    // All 8 axes configured (S86)
    assert(hcdConfig.axes.size == 8)
    ('A' to 'H').foreach(c => assert(hcdConfig.axes.contains(c.toString), s"axis $c missing"))
  }

  test("axes C-H should be linear stages (S86 8-motor example)") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)

    ('C' to 'H').foreach { c =>
      val ax = hcdConfig.axes(c.toString)
      assert(ax.mechanismType == "linear", s"axis $c should be linear")
      assert(ax.algorithm == "forward", s"axis $c should use forward approach")
      assert(ax.upperLimit == 1000.0 && ax.lowerLimit == 0.0, s"axis $c limits")
    }
  }
  
  test("should use default test config as fallback") {
    val defaultConfig = GalilHcdConfig.defaultTestConfig
    
    assert(defaultConfig.controller.hostString == "127.0.0.1")
    assert(defaultConfig.controller.port == 8888)
    assert(defaultConfig.controller.id == 1)
    assert(defaultConfig.controller.embeddedProgram == "galil_embedded_v1.dmc")
    
    assert(defaultConfig.simulate == true)
    
    // A and B active
    assert(defaultConfig.activeAxes.take(2) == Seq(true, true))
    assert(defaultConfig.activeAxes.drop(2).forall(_ == false))
    
    // Both axes configured
    assert(defaultConfig.axes.contains("A"))
    assert(defaultConfig.axes.contains("B"))
  }
  
  // ============================================================
  // Config -> internal state seeding (AxisState.seedFromConfig)
  //
  // S89: indexOffset was written to the controller's hoff[] but never seeded into
  // internal state, so the HMI reported 0.0 for a configured axis and the SDD's
  // "config file is the single source of truth" claim did not hold. The first test
  // below is the guard against that class of omission recurring.
  // ============================================================

  test("axis seed covers exactly the declared set of configured fields") {
    val hcdConfig = GalilHcdConfig.fromConfig(ConfigFactory.load("GalilHcdConfig.conf"))
    val seed = AxisState.seedFromConfig(hcdConfig.axes("A"))

    assert(seed.keySet == AxisState.SeededKeys,
      s"seed/SeededKeys mismatch — missing: ${AxisState.SeededKeys -- seed.keySet}, " +
      s"unexpected: ${seed.keySet -- AxisState.SeededKeys}")
  }

  test("axis seed carries every configured value, including indexOffset (S89 regression)") {
    val hcdConfig = GalilHcdConfig.fromConfig(ConfigFactory.load("GalilHcdConfig.conf"))
    val axisA = hcdConfig.axes("A")
    val seed  = AxisState.seedFromConfig(axisA)

    assert(seed("indexOffset") == axisA.indexOffset)
    assert(axisA.indexOffset != 0.0, "fixture must use a non-zero indexOffset to be meaningful")
    assert(seed("inPositionThreshold") == axisA.inPositionThreshold)
    assert(seed("upperLimit") == axisA.upperLimit)
    assert(seed("lowerLimit") == axisA.lowerLimit)
    assert(seed("maxSpeed") == axisA.maxSpeed)
    assert(seed("acceleration") == axisA.acceleration)
    assert(seed("deceleration") == axisA.deceleration)
    assert(seed("motionDelay") == axisA.motionDelay)
    assert(seed("indexSpeed") == axisA.indexSpeed)
    assert(seed("countsPerRevolution") == axisA.countsPerRevolution)
    assert(seed("mechanismType") == MechanismType.Linear)
    assert(seed("algorithm") == RotatingAlgorithm.Forward)
  }

  test("applying the seed populates AxisState, including indexOffset (S89 regression)") {
    val hcdConfig = GalilHcdConfig.fromConfig(ConfigFactory.load("GalilHcdConfig.conf"))
    val axisB = hcdConfig.axes("B")
    val seeded = AxisState().update(AxisState.seedFromConfig(axisB))

    assert(seeded.indexOffset.contains(axisB.indexOffset))
    assert(seeded.inPositionThreshold == axisB.inPositionThreshold)
    assert(seeded.maxSpeed.contains(axisB.maxSpeed))
    assert(seeded.acceleration.contains(axisB.acceleration))
    assert(seeded.deceleration.contains(axisB.deceleration))
    assert(seeded.indexSpeed.contains(axisB.indexSpeed))
    assert(seeded.motionDelay.contains(axisB.motionDelay))
    assert(seeded.countsPerRevolution.contains(axisB.countsPerRevolution))
    assert(seeded.mechanismType == MechanismType.Rotating)
    assert(seeded.algorithm.contains(RotatingAlgorithm.Shortest))
    assert(seeded.upperLimit.contains(axisB.upperLimit))
    assert(seeded.lowerLimit.contains(axisB.lowerLimit))
  }

  test("a rotating axis with a non-zero indexOffset seeds it (fixture-independent)") {
    val ax = AxisConfig(
      mechanismType = "rotating",
      upperLimit = 360.0,
      lowerLimit = 0.0,
      algorithm = "reverse",
      inPositionThreshold = 1.0,
      indexOffset = 37.5,
      axisName = Some("Test Wheel"),
      countsPerRevolution = 3600.0
    )
    val seeded = AxisState().update(AxisState.seedFromConfig(ax))

    assert(seeded.indexOffset.contains(37.5))
    assert(seeded.algorithm.contains(RotatingAlgorithm.Reverse))
    assert(seeded.axisName.contains("Test Wheel"))
  }

  test("should validate activeAxes has exactly 8 elements") {
    val badConfig = """
      controller {
        host = [127, 0, 0, 1]
        port = 8888
        id = 1
        embeddedProgram = "test.dmc"
      }
      simulate = true
      activeAxes = [true, true]  # Only 2 elements - should fail
      axes { }
    """
    
    val config = ConfigFactory.parseString(badConfig)
    
    assertThrows[IllegalArgumentException] {
      GalilHcdConfig.fromConfig(config)
    }
  }
  
  test("should convert host array to IP string correctly") {
    val controller = ControllerConfig(
      host = Seq(192, 168, 1, 100),
      port = 23,
      id = 1,
      embeddedProgram = "test.dmc"
    )
    
    assert(controller.hostString == "192.168.1.100")
  }
  
  test("mechanism types should be linear or rotating") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)
    
    // Verify all configured axes have valid mechanism types
    hcdConfig.axes.values.foreach { axisConfig =>
      assert(
        axisConfig.mechanismType == "linear" || axisConfig.mechanismType == "rotating",
        s"Invalid mechanism type: ${axisConfig.mechanismType}"
      )
    }
  }
  
  test("algorithms should be forward, reverse, or shortest") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)
    
    // Verify all configured axes have valid algorithms
    hcdConfig.axes.values.foreach { axisConfig =>
      assert(
        axisConfig.algorithm == "forward" || axisConfig.algorithm == "reverse" || axisConfig.algorithm == "shortest",
        s"Invalid algorithm: ${axisConfig.algorithm}"
      )
    }
  }
}