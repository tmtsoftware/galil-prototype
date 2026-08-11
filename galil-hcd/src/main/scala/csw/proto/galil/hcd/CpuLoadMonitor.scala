package csw.proto.galil.hcd

import java.lang.management.ManagementFactory
import java.time.{Duration => JDuration}

import csw.time.scheduler.api.Cancellable
import csw.event.api.scaladsl.EventPublisher
import csw.logging.api.scaladsl.Logger
import csw.params.events.SystemEvent
import csw.prefix.models.Prefix
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`.CpuLoadEvent
import csw.time.scheduler.api.TimeServiceScheduler

import scala.concurrent.duration.FiniteDuration

/**
 * Per-JVM CPU load telemetry.
 *
 * Samples this JVM's CPU usage at a fixed cadence and publishes it as the `cpuLoad`
 * SystemEvent (see `GalilMotionKeys.\`ICS.HCD.GalilMotion\`.CpuLoadEvent` and the HCD
 * `publish-model.conf`).  Two motivations:
 *
 *   1. Verification of REQ-2-APS-0621 — "the CPU load imposed by the APS software on any
 *      computer hardware on which it runs during normal operations shall not exceed 70%".
 *      Summing `processCpuLoad` across the APS JVMs on a host gives exactly that measurand.
 *   2. Live operations resource-health telemetry — a standing signal that is invaluable
 *      when something misbehaves in the field.
 *
 * Why the JDK MXBean rather than `top`/`ps` sampling: `com.sun.management.OperatingSystemMXBean`
 * is pure JDK (zero dependencies) and behaves identically on the macOS development machine and
 * the Linux deployment hardware.  Critically, `getProcessCpuLoad()` and `getCpuLoad()` return a
 * fraction in [0.0, 1.0] ALREADY normalized to the whole machine (1.0 == every logical CPU busy
 * 100% of the sample window), so there is no per-core arithmetic to get wrong and the 70% ceiling
 * maps directly onto 0.70.
 *
 * Aggregation note: `getProcessCpuLoad()` is per-JVM-PROCESS, not per-CSW-component.  Every
 * component sharing a JVM observes the SAME whole-JVM value, so any consumer that sums APS load
 * across a host must de-duplicate by (hostname, pid) — both are carried in every event — taking
 * one representative reading per process before summing.
 */
object CpuLoadMonitor {

  /** Immutable CPU reading captured from the OS MX bean.  Pure and therefore unit-testable. */
  final case class Sample(processCpuLoad: Double, systemCpuLoad: Double, availableProcessors: Int)

  /**
   * Canonical source prefix for the COMBINED ICS assembly container's single per-JVM
   * cpuLoad event. All motion assemblies run in one JVM (IcsAssembliesContainer), so the
   * monitor publishes ONE event under this fixed prefix rather than under whichever
   * component won the startOnce race. Consumers (AssemblyLoadApp, aps-ics-ui) key off it.
   */
  val AssemblyContainerPrefix: Prefix = Prefix("APS.ICS.IcsAssemblies")

  // Process identity is fixed for the life of the JVM; compute once.
  private val ProcessId: Int =
    ProcessHandle.current().pid().toInt // real OS PIDs fit in 32 bits on Linux/macOS

  private val HostName: String =
    try java.net.InetAddress.getLocalHost.getHostName
    catch { case _: Throwable => "unknown" }

  /**
   * Read the current CPU load from the given bean.  A value < 0 means "not yet available"
   * (the JDK contract for the first sample or when the platform cannot supply it); such
   * readings are passed through unchanged and are expected to be filtered by consumers.
   */
  def sample(os: com.sun.management.OperatingSystemMXBean): Sample =
    Sample(
      processCpuLoad = os.getProcessCpuLoad(),
      systemCpuLoad = os.getCpuLoad(),
      availableProcessors = os.getAvailableProcessors()
    )

  /** Build the `cpuLoad` SystemEvent for a component from a sample.  Pure and unit-testable. */
  def buildEvent(sourcePrefix: Prefix, s: Sample): SystemEvent =
    SystemEvent(sourcePrefix, CpuLoadEvent.eventKey.eventName).madd(
      CpuLoadEvent.processCpuLoadKey.set(s.processCpuLoad.toFloat),
      CpuLoadEvent.systemCpuLoadKey.set(s.systemCpuLoad.toFloat),
      CpuLoadEvent.availableProcessorsKey.set(s.availableProcessors),
      CpuLoadEvent.pidKey.set(ProcessId),
      CpuLoadEvent.hostnameKey.set(HostName)
    )

  // ── Per-JVM singleton lifecycle ─────────────────────────────────────────
  // cpuLoad is a per-PROCESS metric (getProcessCpuLoad covers the whole JVM), so
  // co-located CSW components must NOT each publish it — that would emit N identical
  // events and N-times overcount any host-level sum. startOnce ensures exactly one
  // monitor (hence one cpuLoad event) per JVM, whichever component starts first.
  private val started = new java.util.concurrent.atomic.AtomicBoolean(false)
  @volatile private var runner: Option[CpuLoadMonitor] = None

  /** Start the JVM's single CPU-load monitor. Idempotent across the process: the
   *  first caller wins and later callers (co-located components, or a CSW Restart)
   *  are no-ops, so the monitor survives component restarts and there is exactly
   *  one cpuLoad publisher per JVM. */
  def startOnce(
      sourcePrefix: Prefix,
      publisher: EventPublisher,
      scheduler: TimeServiceScheduler,
      log: Logger,
      interval: FiniteDuration
  ): Unit =
    if (started.compareAndSet(false, true)) {
      // The caller passes the source prefix explicitly (the HCD its own component prefix;
      // the assembly container the fixed AssemblyContainerPrefix), so no indirection here.
      val r = new CpuLoadMonitor(sourcePrefix, publisher, scheduler, log, interval)
      r.start()
      runner = Some(r)
    }

  /** Stop the JVM's monitor if running (idempotent). Deliberately NOT wired into
   *  per-component onShutdown: in a multi-component container that would stop
   *  telemetry while other components still run. The monitor is a JVM-lifetime
   *  daemon whose scheduler is cancelled when the ActorSystem terminates; this is
   *  provided for tests and explicit JVM-level shutdown. */
  def stopOnce(): Unit = {
    runner.foreach(_.stop())
    runner = None
    started.set(false)
  }

  /** Latest sample from the JVM's monitor, or None if not started / no tick yet. */
  def latest: Option[Sample] = runner.flatMap(_.latest)
}

/**
 * Lifecycle wrapper around [[CpuLoadMonitor]]: `start()` schedules periodic sampling and
 * publishing on the CSW TimeServiceScheduler; `stop()` cancels it.  Instantiated once per
 * JVM via the companion object's `startOnce` (a process may host many CSW components but
 * needs only one CPU-load publisher).
 *
 * @param sourcePrefix the publishing component's prefix (the event source; carries instance suffix)
 * @param publisher    the component's default event publisher
 * @param scheduler    the CSW time-service scheduler (from CswContext)
 * @param log          the component logger
 * @param interval     sampling / publishing cadence (1 Hz matches the model file's maxRate)
 */
class CpuLoadMonitor(
    sourcePrefix: Prefix,
    publisher: EventPublisher,
    scheduler: TimeServiceScheduler,
    log: Logger,
    interval: FiniteDuration
) {
  import CpuLoadMonitor._

  private val osBean: com.sun.management.OperatingSystemMXBean =
    ManagementFactory.getPlatformMXBean(classOf[com.sun.management.OperatingSystemMXBean])

  @volatile private var timer: Option[Cancellable] = None

  private val latestRef =
    new java.util.concurrent.atomic.AtomicReference[Option[Sample]](None)

  /** Most recent sample, or None until the first tick. Thread-safe; read by the HMI push. */
  def latest: Option[Sample] = latestRef.get()

  /** Idempotent: starts periodic publishing if not already running. */
  def start(): Unit = synchronized {
    if (timer.isEmpty) {
      log.info(
        s"Starting CPU load monitor for $sourcePrefix " +
          s"(interval=$interval, cores=${osBean.getAvailableProcessors()})"
      )
      val jInterval = JDuration.ofNanos(interval.toNanos)
      timer = Some(scheduler.schedulePeriodically(jInterval) {
        try {
          val s = sample(osBean)
          latestRef.set(Some(s))
          val _ = publisher.publish(buildEvent(sourcePrefix, s))
        } catch {
          case ex: Throwable => log.warn(s"cpuLoad publish failed: ${ex.getMessage}")
        }
      })
    }
  }

  /** Idempotent: cancels periodic publishing if running. */
  def stop(): Unit = synchronized {
    timer.foreach(_.cancel())
    timer = None
    log.debug("CPU load monitor stopped")
  }
}
