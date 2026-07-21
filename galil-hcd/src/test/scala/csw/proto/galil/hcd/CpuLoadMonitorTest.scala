package csw.proto.galil.hcd

import java.lang.management.ManagementFactory

import csw.prefix.models.{Prefix, Subsystem}
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`.CpuLoadEvent
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for [[CpuLoadMonitor]].  These exercise the pure parts (sample / buildEvent);
 * the scheduling/publishing wrapper is intentionally not covered here (it is a thin adapter
 * over the CSW TimeServiceScheduler + EventPublisher, better exercised in an integration run).
 */
class CpuLoadMonitorTest extends AnyFunSuite with Matchers {

  // An instance-suffixed prefix, as a running controller (1-4) would carry.
  private val testPrefix = Prefix(Subsystem.APS, "ICS.HCD.GalilMotion.1")

  test("buildEvent maps a Sample onto the cpuLoad event keys, with the component as source") {
    val s  = CpuLoadMonitor.Sample(processCpuLoad = 0.42, systemCpuLoad = 0.63, availableProcessors = 8)
    val ev = CpuLoadMonitor.buildEvent(testPrefix, s)

    ev.source shouldBe testPrefix
    ev.eventName shouldBe CpuLoadEvent.eventKey.eventName
    ev(CpuLoadEvent.processCpuLoadKey).head shouldBe (0.42f +- 1e-6f)
    ev(CpuLoadEvent.systemCpuLoadKey).head shouldBe (0.63f +- 1e-6f)
    ev(CpuLoadEvent.availableProcessorsKey).head shouldBe 8
    ev(CpuLoadEvent.pidKey).head should be > 0
    ev(CpuLoadEvent.hostnameKey).head should not be empty
  }

  test("buildEvent passes through a negative (not-yet-available) load reading unchanged") {
    val s  = CpuLoadMonitor.Sample(processCpuLoad = -1.0, systemCpuLoad = -1.0, availableProcessors = 8)
    val ev = CpuLoadMonitor.buildEvent(testPrefix, s)
    ev(CpuLoadEvent.processCpuLoadKey).head shouldBe (-1.0f +- 1e-6f)
    ev(CpuLoadEvent.systemCpuLoadKey).head shouldBe (-1.0f +- 1e-6f)
  }

  test("sample reads the live JDK OperatingSystemMXBean") {
    val os = ManagementFactory.getPlatformMXBean(classOf[com.sun.management.OperatingSystemMXBean])
    val s  = CpuLoadMonitor.sample(os)
    s.availableProcessors should be > 0
    // Each load is a fraction in [0.0, 1.0], or negative when a reading is not yet available.
    s.processCpuLoad should be <= 1.0
    s.systemCpuLoad should be <= 1.0
  }
}
