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
    
    // Active axes - A and B active, C-H inactive
    assert(hcdConfig.activeAxes.length == 8)
    assert(hcdConfig.activeAxes(0) == true)  // A
    assert(hcdConfig.activeAxes(1) == true)  // B
    assert(hcdConfig.activeAxes(2) == false) // C
    assert(hcdConfig.activeAxes(3) == false) // D
    assert(hcdConfig.activeAxes(4) == false) // E
    assert(hcdConfig.activeAxes(5) == false) // F
    assert(hcdConfig.activeAxes(6) == false) // G
    assert(hcdConfig.activeAxes(7) == false) // H
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
  }
  
  test("should only contain configurations for active axes") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)
    
    // Only A and B should be configured
    assert(hcdConfig.axes.size == 2)
    assert(hcdConfig.axes.contains("A"))
    assert(hcdConfig.axes.contains("B"))
    assert(!hcdConfig.axes.contains("C"))
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
  
  test("algorithms should be forward or shortest") {
    val config = ConfigFactory.load("GalilHcdConfig.conf")
    val hcdConfig = GalilHcdConfig.fromConfig(config)
    
    // Verify all configured axes have valid algorithms
    hcdConfig.axes.values.foreach { axisConfig =>
      assert(
        axisConfig.algorithm == "forward" || axisConfig.algorithm == "shortest",
        s"Invalid algorithm: ${axisConfig.algorithm}"
      )
    }
  }
}