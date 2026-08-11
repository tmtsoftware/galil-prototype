package csw.proto.galil.client

import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import org.apache.pekko.util.Timeout
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.HCD
import csw.location.api.models.Connection.PekkoConnection
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.{CommandName, Setup}
import csw.params.commands.CommandResponse
import csw.params.commands.CommandResponse.Completed
import csw.params.core.models.Choice
import csw.prefix.models.Prefix
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._
import csw.time.core.models.TAITime

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

/**
 * Standalone test program that streams `trackAxis` commands to the Galil HCD to
 * exercise PVT tracking.  Plays the role of the K-Mirror Assembly in lab tests,
 * letting us validate the HCD's PVT pipeline end-to-end before a real Assembly
 * exists.
 *
 * The injector ticks at a configurable cadence (typically 1 Hz, matching the
 * TCS PupilRotation event rate).  On each tick it computes `(position, rate)`
 * from the chosen trajectory shape at `validTime = now + leadTime`, builds a
 * `trackAxis` Setup with the new ICD keys (position/rate/validTime), and
 * submits it.  When `--duration` elapses the injector emits one final segment
 * with `rate = 0` to bring the motor cleanly to rest, then submits `stopAxis`.
 *
 * The shape's clock origin (`t = 0`) is the moment of the first submission.
 * That first segment is the HCD-side session start: the HCD synthesizes
 * `prev_validTime = now` and `prev_position = current motor position` (from
 * its polled state), so the first PVA segment ramps the motor from its
 * current position/rest to the injector's `position(leadTime)`/`rate(leadTime)`
 * over the lead-time interval.  The Assembly (and this injector) doesn't need
 * to know or care about that startup transient — it just keeps emitting.
 *
 * Trajectory shapes:
 *
 *  - `constant`: position ramps linearly at the given rate.
 *      `position(t) = position0 + rate * t`,  `rate(t) = rate` (deg/sec).
 *      Equivalent to commanding a JG at `rate` — but via PVT, so we exercise
 *      the streaming path.
 *
 *  - `sinusoid`: angular oscillation, useful for testing both ΔP and V_end
 *      correctness over a curve.
 *      `position(t) = center + amplitude * sin(2π·f·t)`
 *      `rate(t)     = 2π·f·amplitude * cos(2π·f·t)`
 *      where amplitude is in degrees, frequency in Hz.
 *
 * Both shapes append a "decel-to-zero" tail at duration end: one final segment
 * with `rate = 0` at the current shape position, so the cubic-Hermite ramps
 * the motor to rest.
 *
 * CLI:
 *   TrackInjectorApp \
 *     --axis A \
 *     --shape (constant|sinusoid) \
 *     [--rate <deg/sec>]              (constant)
 *     [--amplitude <deg>]             (sinusoid)
 *     [--frequency <Hz>]              (sinusoid)
 *     [--center <deg>]                (sinusoid; default 0)
 *     [--position0 <deg>]             (constant; default = current motor position)
 *     --duration <sec> \
 *     --cadence-hz <Hz> \
 *     --lead-time <sec>
 *
 * Example — sinusoid, ±30°, 0.05 Hz (20-second period), for 60 seconds, 1 Hz
 * cadence, 2 second lead:
 *   sbt "galil-client/runMain csw.proto.galil.client.TrackInjectorApp \
 *        --axis A --shape sinusoid --amplitude 30 --frequency 0.05 \
 *        --duration 60 --cadence-hz 1 --lead-time 2"
 *
 * Ctrl-C: the registered shutdown hook submits stopAxis and exits.  Skipping
 * this would leave the HCD's tracking session active until the FIFO empties
 * and underrun fires — annoying but not unsafe.
 *
 * Prerequisites:
 *  1. CSW services running (csw-services start)
 *  2. Galil HCD running and registered
 *  3. Target axis homed (or in a known Idle state) before invocation
 */
object TrackInjectorApp {

  private case class Config(
    axis: String                  = "A",
    shape: String                 = "sinusoid",
    rate: Double                  = 30.0,        // constant: deg/sec
    amplitude: Double             = 30.0,        // sinusoid: deg peak
    frequency: Double             = 0.05,        // sinusoid: Hz
    center: Double                = 0.0,         // sinusoid: deg
    position0: Option[Double]     = None,        // constant: starting position deg
    durationSec: Double           = 30.0,
    cadenceHz: Double             = 1.0,
    leadMarginSec: Double         = 0.2,         // slack in FIFO beyond cadence period
    hcdComponent: String          = "ICS.HCD.GalilMotion"   // component name under APS subsystem
  )

  private def parseArgs(args: Array[String]): Config = {
    var cfg = Config()
    var i = 0
    while i < args.length do
      val flag = args(i)
      def next: String =
        if i + 1 >= args.length then sys.error(s"Missing value for $flag") else { i += 1; args(i) }
      flag match
        case "--axis"        => cfg = cfg.copy(axis = next.toUpperCase)
        case "--shape"       => cfg = cfg.copy(shape = next.toLowerCase)
        case "--rate"        => cfg = cfg.copy(rate = next.toDouble)
        case "--amplitude"   => cfg = cfg.copy(amplitude = next.toDouble)
        case "--frequency"   => cfg = cfg.copy(frequency = next.toDouble)
        case "--center"      => cfg = cfg.copy(center = next.toDouble)
        case "--position0"   => cfg = cfg.copy(position0 = Option(next.toDouble))
        case "--duration"    => cfg = cfg.copy(durationSec = next.toDouble)
        case "--cadence-hz"  => cfg = cfg.copy(cadenceHz = next.toDouble)
        case "--lead-margin" => cfg = cfg.copy(leadMarginSec = next.toDouble)
        case "--hcd-component" => cfg = cfg.copy(hcdComponent = next)
        case "--help" | "-h" =>
          printUsage(); sys.exit(0)
        case other =>
          sys.error(s"Unknown flag: $other (use --help)")
      i += 1
    validate(cfg)
    cfg
  }

  private def validate(cfg: Config): Unit = {
    require(cfg.axis.length == 1 && "ABCDEFGH".contains(cfg.axis),
            s"--axis must be a single letter A-H, got '${cfg.axis}'")
    require(Set("constant", "sinusoid").contains(cfg.shape),
            s"--shape must be 'constant' or 'sinusoid', got '${cfg.shape}'")
    require(cfg.cadenceHz > 0, s"--cadence-hz must be > 0")
    require(cfg.leadMarginSec > 0, s"--lead-margin must be > 0")
    require(cfg.durationSec > 0, s"--duration must be > 0")
    // Lead margin under ~50 ms is risky: HCD/network/scheduler jitter can make
    // the new segment land at the HCD after the previous segment's validTime,
    // triggering underrun even though the math is correct in steady state.
    if cfg.leadMarginSec < 0.05 then
      Console.err.println(s"WARNING: lead-margin ${cfg.leadMarginSec}s is very small; " +
        s"underrun may fire from normal scheduler jitter.  0.1-0.5s is typical.")
    // First-segment T cap: TrackInjector's first segment uses validTime = now +
    // cadencePeriod + leadMargin.  The HCD enforces a 2.048s upper bound on
    // PVA's T argument (Galil DMC-40x0 PV reference), so the sum here must
    // fit under that.  We use 2.0s as the trigger threshold to leave headroom
    // for clock skew and scheduling jitter; the HCD's own bound is the hard
    // limit and will reject anything beyond 2.048s.  Reject at config time so
    // the operator gets a clear error rather than an HCD rejection on the
    // first segment.
    val firstSegmentSec = (1.0 / cfg.cadenceHz) + cfg.leadMarginSec
    require(firstSegmentSec <= 2.0,
            s"First-segment validTime gap (1/cadenceHz + leadMargin) = " +
            f"${firstSegmentSec}%.3fs exceeds the controller's 2.048s PVA T-bound. " +
            f"With cadence-hz=${cfg.cadenceHz}%.3f and lead-margin=${cfg.leadMarginSec}%.3fs, " +
            s"the first segment would be too long for the controller to accept. " +
            s"Reduce lead-margin or increase cadence-hz so their sum stays under 2 seconds.")
  }

  private def printUsage(): Unit = {
    println("""TrackInjectorApp — streams trackAxis commands to exercise PVT tracking.
              |
              |Common flags:
              |  --axis <A-H>           Axis designator (default A)
              |  --shape <c|s>          constant | sinusoid (default sinusoid)
              |  --duration <sec>       How long to stream (default 30)
              |  --cadence-hz <Hz>      Submission rate (default 1.0)
              |  --lead-margin <sec>    Slack in FIFO beyond cadence period.  validTime =
              |                         now + (1/cadence) + leadMargin.  Default 0.2.
              |                         Typical 0.1-0.5; <0.05 risks jitter-induced underrun.
              |  --hcd-component <name> HCD component name to locate, under the APS subsystem
              |                         (default ICS.HCD.GalilMotion; use ICS.HCD.GalilMotion.1
              |                         for STB, ICS.HCD.GalilMotion.0 for simulator, etc.)
              |
              |Constant-rate shape:
              |  --rate <deg/sec>       Slew rate (default 30)
              |  --position0 <deg>      Starting position (default: current motor pos)
              |
              |Sinusoid shape:
              |  --amplitude <deg>      Peak deviation (default 30)
              |  --frequency <Hz>       Oscillation frequency (default 0.05)
              |  --center <deg>         Mean position (default 0)
              |""".stripMargin)
  }

  // ----- Trajectory functions: (t since start, in seconds) → (position deg, rate deg/sec) -----

  private trait Trajectory:
    /** t in seconds since session start. */
    def at(t: Double): (Double, Double)

  private class ConstantRate(position0: Double, rate: Double) extends Trajectory:
    def at(t: Double): (Double, Double) = (position0 + rate * t, rate)

  private class Sinusoid(center: Double, amplitude: Double, frequencyHz: Double) extends Trajectory:
    private val omega = 2.0 * math.Pi * frequencyHz
    def at(t: Double): (Double, Double) =
      val pos = center + amplitude * math.sin(omega * t)
      val rate = amplitude * omega * math.cos(omega * t)
      (pos, rate)

  // ----- Main -----

  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] =
    ActorSystem(SpawnProtocol(), "TrackInjectorApp")
  implicit lazy val ec: ExecutionContextExecutor = typedSystem.executionContext
  implicit val timeout: Timeout                  = Timeout(10.seconds)

  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val source          = Prefix("CSW.TrackInjector")

  private val running = new AtomicBoolean(true)

  def main(args: Array[String]): Unit = {
    val cfg = parseArgs(args)

    val host = InetAddress.getLocalHost.getHostName
    LoggingSystemFactory.start("TrackInjectorApp", "0.1", host, typedSystem)
    val log = GenericLoggerFactory.getLogger

    log.info(s"TrackInjector starting: $cfg")

    // Build the location-service connection from the CLI-supplied component name.
    // Subsystem is fixed at APS — the HCD always registers under APS — but the
    // component-name string varies (e.g. "ICS.HCD.GalilMotion" for the lab HCD,
    // "ICS.HCD.GalilMotion.1" for the STB, "ICS.HCD.GalilMotion.0" for a sim).
    val hcdPrefix = Prefix(csw.prefix.models.Subsystem.APS, cfg.hcdComponent)
    val connection = PekkoConnection(ComponentId(hcdPrefix, HCD))
    log.info(s"Resolving HCD at $hcdPrefix")

    // Resolve HCD via location service.
    val hcd: CommandService = Await.result(
      locationService.resolve(connection, 30.seconds).map {
        case Some(loc) => CommandServiceFactory.make(loc)
        case None      => sys.error(s"Could not locate Galil HCD '$hcdPrefix' via location service")
      },
      35.seconds
    )
    log.info("HCD located.")

    val trajectory: Trajectory = cfg.shape match
      case "constant" =>
        val pos0 = cfg.position0.getOrElse(0.0)
        new ConstantRate(pos0, cfg.rate)
      case "sinusoid" =>
        new Sinusoid(cfg.center, cfg.amplitude, cfg.frequency)
      case other =>
        sys.error(s"Unreachable: validated shape was '$other'")

    // Ctrl-C handler — submit stopAxis and exit cleanly.
    val shutdownHook = new Thread(() => {
      if running.getAndSet(false) then
        log.info("Shutdown signal received; stopping axis.")
        try
          val stopSetup = Setup(source, CommandName("stopAxis"), None)
            .add(StopAxisCommand.axisKey.set(Choice(cfg.axis)))
          val resp = Await.result(hcd.submitAndWait(stopSetup), 10.seconds)
          log.info(s"stopAxis response: $resp")
        catch
          case ex: Exception => log.error(s"stopAxis failed in shutdown: ${ex.getMessage}")
        typedSystem.terminate()
    })
    Runtime.getRuntime.addShutdownHook(shutdownHook)

    runInjector(cfg, hcd, trajectory, log)

    // Normal end-of-duration path: submit stopAxis to end the session.
    if running.getAndSet(false) then
      submitFinalStop(cfg, hcd, log)
    Runtime.getRuntime.removeShutdownHook(shutdownHook)

    typedSystem.terminate()
    Await.result(typedSystem.whenTerminated, 10.seconds)
    System.exit(0)
  }

  /**
   * Main streaming loop.  Submits one trackAxis per cadence tick until duration
   * elapses or the running flag is cleared.  Returns when streaming is done; the
   * caller emits the final stop sequence.
   */
  private def runInjector(
    cfg: Config,
    hcd: CommandService,
    trajectory: Trajectory,
    log: csw.logging.api.scaladsl.Logger
  ): Unit = {
    val cadencePeriodMillis = (1000.0 / cfg.cadenceHz).toLong
    val durationMillis      = (cfg.durationSec * 1000.0).toLong
    val leadMarginMillis    = (cfg.leadMarginSec * 1000.0).toLong
    // Each segment's validTime = now + cadence_period + margin.  The cadence_period
    // component ensures the new segment's validTime is at least later than when the
    // NEXT submission will land (one cadence period from now); the margin component
    // provides additional FIFO slack to absorb HCD/network/scheduler jitter.
    val leadMillis          = cadencePeriodMillis + leadMarginMillis

    val startWall = System.currentTimeMillis()
    var tickIdx = 0L

    while running.get() do
      val wallNow = System.currentTimeMillis()
      val elapsedMillis = wallNow - startWall

      if elapsedMillis >= durationMillis then
        log.info(s"Duration ${cfg.durationSec}s elapsed; ending stream.")
        return

      // Compute (position, rate) at the moment the segment will be valid (not "now").
      val validTimeInstant = Instant.ofEpochMilli(wallNow + leadMillis)
      val tValid = (elapsedMillis + leadMillis) / 1000.0  // seconds since session start

      val (positionDeg, rateDegPerSec) = trajectory.at(tValid)

      val setup = Setup(source, CommandName("trackAxis"), None)
        .add(TrackAxisCommand.axisKey.set(Choice(cfg.axis)))
        .add(TrackAxisCommand.positionKey.set(positionDeg.toFloat))
        .add(TrackAxisCommand.rateKey.set(rateDegPerSec.toFloat))
        .add(TrackAxisCommand.validTimeKey.set(TAITime(validTimeInstant)))

      // Submit-and-forget: we don't await per-tick (would jitter the cadence).
      // Track failures asynchronously so the loop keeps cadence on transient
      // hiccups — but on any non-Completed response, halt the stream.  The HCD
      // rejects (Error) for validation, bounds, or wire failures; once one has
      // fired, continuing means submitting more segments that will fail the
      // same way.  Set running := false so the next loop-condition check exits;
      // the existing end-of-loop path then submits stopAxis to clean up.
      val submitted: Future[CommandResponse] = hcd.submitAndWait(setup)
      val capturedTick = tickIdx
      submitted.foreach {
        case _: Completed => // happy path, nothing to log
        case other         =>
          log.warn(s"tick=$capturedTick trackAxis response: $other")
          if running.compareAndSet(true, false) then
            log.warn(s"tick=$capturedTick halting injection: first HCD rejection received; " +
                     s"the loop will exit on next iteration and stopAxis will be submitted")
      }
      submitted.failed.foreach { ex =>
        log.warn(s"tick=$capturedTick trackAxis future failed: ${ex.getMessage}")
        if running.compareAndSet(true, false) then
          log.warn(s"tick=$capturedTick halting injection: trackAxis future failed; " +
                   s"the loop will exit on next iteration and stopAxis will be submitted")
      }

      if tickIdx % math.max(1L, cfg.cadenceHz.toLong * 5L) == 0L then
        log.info(f"tick=$tickIdx tValid=${tValid}%.2fs pos=${positionDeg}%.2f° " +
                 f"rate=${rateDegPerSec}%.2f°/s validTime=$validTimeInstant")

      tickIdx += 1

      // Sleep to next tick.  We use wall-clock pacing rather than fixed sleep
      // so cumulative drift doesn't accumulate over a long run.
      val nextTickWall = startWall + tickIdx * cadencePeriodMillis
      val sleepMillis = nextTickWall - System.currentTimeMillis()
      if sleepMillis > 0 then Thread.sleep(sleepMillis)
  }

  /**
   * End-of-stream stop.  Submits stopAxis, which (per the HCD's PVT design)
   * drains the controller's PVT FIFO with ST<x> and runs the application
   * stop sequence (#StopX), bringing the motor to rest and transitioning the
   * axis to Idle.  Any segments still in the FIFO at this point are abandoned.
   *
   * A possible refinement would be to submit one final trackAxis with rate=0
   * at the current shape position so the motor decelerates smoothly via PVT
   * before the stopAxis arrives — useful if the deceleration profile of
   * #StopX's ST is too abrupt for the application.  Not done here; the lab
   * steppers tolerate hard stops fine.
   */
  private def submitFinalStop(
    cfg: Config,
    hcd: CommandService,
    log: csw.logging.api.scaladsl.Logger
  ): Unit = {
    log.info("Streaming finished; submitting stopAxis to end the session.")
    try
      val stopSetup = Setup(source, CommandName("stopAxis"), None)
        .add(StopAxisCommand.axisKey.set(Choice(cfg.axis)))
      val resp = Await.result(hcd.submitAndWait(stopSetup), 10.seconds)
      log.info(s"stopAxis response: $resp")
    catch
      case ex: Exception =>
        log.error(s"stopAxis failed at end of stream: ${ex.getMessage}")
  }
}