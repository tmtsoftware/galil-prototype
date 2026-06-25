package aps.ics.sim

import java.net.InetAddress

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import csw.event.client.EventServiceFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.events.SystemEvent
import csw.time.core.models.TAITime

import scala.concurrent.ExecutionContextExecutor

/**
 * Standalone TCS PupilRotation simulator (SDD §8.3). Publishes the TCS
 * [[TcsPupilRotation]] event so the K-Mirror assembly's Tracking Control Actor can
 * be exercised without a real TCS — the K-Mirror is the only assembly dependent on
 * an external subsystem.
 *
 * Both modes are driven by the SAME field-rotation (parallactic-angle) model, so the
 * slew pre-stage and the track stream agree on the start position — the K-Mirror
 * slews to exactly where tracking begins, with no jump at the SLEWING -> TRACKING
 * handoff. The pupil rotation is the parallactic angle q of a target at the given
 * declination observed from the given site latitude, at hour angle HA:
 *     q = atan2( sin(HA),  tan(lat)*cos(dec) - sin(dec)*cos(HA) )
 *
 *   - **slew** (default; pair with the K-Mirror in SLEWING): publishes a CONSTANT
 *     demand = q(HA0), the predicted track-start angle. The K-Mirror pre-stages to it
 *     and reaches SLEW_COMPLETE. (The published rate is q's instantaneous rate at HA0;
 *     SLEWING ignores rate.)
 *
 *   - **track** (pair with the K-Mirror in TRACKING): publishes the live stream as HA
 *     advances at the sidereal rate from HA0, with q and dq/dt evaluated at validTime.
 *     The K-Mirror streams trackAxis(position, rate, validTime). The sidereal
 *     field-rotation rate is small (~0.004 deg/s); `--time-scale` accelerates
 *     simulated time so convergence dynamics are observable in testing.
 *
 * Args (all optional):
 *   --mode <slew|track>    sim mode                                  (default slew)
 *   --ha0 <deg>            hour angle of the (slew) start / track t=0 (default 30.0)
 *   --dec <deg>            target declination                        (default 0.0)
 *   --latitude <deg>       observing site latitude                   (default 19.82)
 *   --time-scale <factor>  simulated-time speed-up (track)           (default 1.0)
 *   --rate-hz <hz>         publish cadence                           (default 1.0)
 *   --lead <sec>           validTime lead ahead of now               (default 1.2)
 *
 * Run against a running CSW event service (Location + Event servers up). Ctrl-C to stop.
 */
object TcsPupilRotationSimApp:

  /** Sidereal rotation rate of the hour angle (deg per real second). */
  private val SiderealDegPerSec: Double = 360.0 / 86164.0905

  /** Forward-difference step (real seconds) for the instantaneous rate. */
  private val RateDeltaSec: Double = 0.05

  def main(args: Array[String]): Unit =
    given system: ActorSystem[SpawnProtocol.Command] =
      ActorSystem(SpawnProtocol(), "TcsPupilRotationSim")
    given ec: ExecutionContextExecutor = system.executionContext

    val host = InetAddress.getLocalHost.getHostName
    LoggingSystemFactory.start("TcsPupilRotationSim", "0.1", host, system)
    val log = GenericLoggerFactory.getLogger

    val mode      = argString(args, "--mode", "slew").toLowerCase
    val ha0Deg    = argDouble(args, "--ha0", 30.0)
    val decDeg    = argDouble(args, "--dec", 0.0)
    val latDeg    = argDouble(args, "--latitude", 19.82)
    val timeScale = argDouble(args, "--time-scale", 1.0)
    val cadenceHz = argDouble(args, "--rate-hz", 1.0)
    val leadSec   = argDouble(args, "--lead", 1.2)
    val cadenceMs = math.max(1L, (1000.0 / (if cadenceHz > 0 then cadenceHz else 1.0)).toLong)
    val leadMs    = (leadSec * 1000.0).toLong

    val tracking = mode == "track"

    // Parallactic angle (deg) for a given hour angle.
    def parallacticDeg(haDeg: Double): Double =
      val ha = math.toRadians(haDeg)
      val d  = math.toRadians(decDeg)
      val l  = math.toRadians(latDeg)
      math.toDegrees(math.atan2(math.sin(ha), math.tan(l) * math.cos(d) - math.sin(d) * math.cos(ha)))

    // (pupil rotation, instantaneous rate deg/s) at a given hour angle. Rate is the
    // forward difference over the HA the sidereal rate covers in RateDeltaSec real s.
    def stateAt(haDeg: Double): (Double, Double) =
      val haStep = SiderealDegPerSec * timeScale * RateDeltaSec
      val q1     = parallacticDeg(haDeg)
      val q2     = parallacticDeg(haDeg + haStep)
      (q1, (q2 - q1) / RateDeltaSec)

    // Hour angle (deg) at `elapsedSec` real seconds after program start, scaled.
    def haDegAt(elapsedSec: Double): Double = ha0Deg + SiderealDegPerSec * timeScale * elapsedSec

    val locationService = HttpLocationServiceFactory.makeLocalClient
    val eventService    = new EventServiceFactory().make(locationService)
    val publisher       = eventService.defaultPublisher

    val (startQ, startRate) = stateAt(ha0Deg)
    if tracking then
      log.info(
        s"TCS PupilRotation sim [track] -> ${TcsPupilRotation.eventKey}: " +
          s"ha0=$ha0Deg deg, dec=$decDeg deg, lat=$latDeg deg, timeScale=$timeScale -> " +
          s"start pupilRotation=${"%.3f".format(startQ)} deg; cadence=${cadenceMs}ms, lead=${leadSec}s"
      )
    else
      log.info(
        s"TCS PupilRotation sim [slew] -> ${TcsPupilRotation.eventKey}: " +
          s"ha0=$ha0Deg deg, dec=$decDeg deg, lat=$latDeg deg -> predicted start " +
          s"pupilRotation=${"%.3f".format(startQ)} deg (rate ${"%.5f".format(startRate)} deg/s); " +
          s"cadence=${cadenceMs}ms, lead=${leadSec}s"
      )

    sys.addShutdownHook {
      log.info("TCS PupilRotation sim stopping")
      system.terminate()
    }

    val startNanos = System.nanoTime()

    while true do
      val validTime = TAITime(TAITime.now().value.plusMillis(leadMs))
      val (rotation, rotationRate) =
        if tracking then
          val elapsedAtValid = (System.nanoTime() - startNanos) / 1e9 + leadSec
          stateAt(haDegAt(elapsedAtValid))
        else (startQ, startRate)

      val ev = SystemEvent(TcsPupilRotation.prefix, TcsPupilRotation.eventName).madd(
        TcsPupilRotation.rotationKey.set(rotation),
        TcsPupilRotation.rotationRateKey.set(rotationRate),
        TcsPupilRotation.validTimeKey.set(validTime)
      )
      publisher.publish(ev)
      Thread.sleep(cadenceMs)

  /** Minimal `--key value` double parser (returns default if absent/unparseable). */
  private def argDouble(args: Array[String], key: String, default: Double): Double =
    val i = args.indexOf(key)
    if i >= 0 && i + 1 < args.length then args(i + 1).toDoubleOption.getOrElse(default)
    else default

  /** Minimal `--key value` string parser (returns default if absent). */
  private def argString(args: Array[String], key: String, default: String): String =
    val i = args.indexOf(key)
    if i >= 0 && i + 1 < args.length then args(i + 1) else default