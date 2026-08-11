package aps.ics.assembly.foc

import org.apache.pekko.actor.typed.{Behavior, PostStop}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import csw.command.api.scaladsl.CommandService
import csw.event.api.scaladsl.EventService
import csw.logging.api.scaladsl.Logger
import csw.params.commands.CommandResponse._
import csw.params.commands.Setup
import csw.params.core.models.Choice
import csw.params.events.{Event, SystemEvent}
import csw.prefix.models.Prefix
import csw.time.core.models.TAITime

import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion` as Hcd
import aps.ics.assembly.icd.FocKMirrorKeys.`ICS.FOC.KMirror` as KM
import aps.ics.sim.TcsPupilRotation

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * K-Mirror Tracking Control Actor (SDD §8.6.2). A long-lived child actor created by
 * [[FocKMirrorHandlers]] at assembly startup. It subscribes to the TCS PupilRotation
 * event and acts according to the assembly operating mode:
 *
 *   - MANUAL (§8.6.2.1): sends nothing to the HCD.
 *
 *   - SLEWING (§8.6.2.2): the TCS event carries the FIXED predicted pupil angle for
 *     the upcoming track. On a demand that differs from the last submitted by more
 *     than the in-position threshold, the actor issues a single HCD `positionAxis`
 *     (degrees -> COUNTS) to pre-stage the K-Mirror; when that move completes in
 *     position, slewModeState -> SLEW_COMPLETE, gating setMode(TRACKING).
 *
 *   - TRACKING (§8.6.2.3): on each TCS event the actor computes the position/rate
 *     demand (§8.2.2.2) and streams it to the HCD `trackAxis`. trackAxis for a
 *     rotating axis takes DEGREES and deg/sec (the HCD converts to counts and owns
 *     the PVT/shortest-arc math), so the actor passes degrees through unconverted.
 *     The trackingModeState machine (NOT_CONVERGED -> CONVERGED -> ..._WITH_PIT ->
 *     TRACKING_ERROR) is driven by the live HCD position vs demand; trackingMetrics
 *     is published each cycle and trackingError on entering the error state.
 *
 * Demand (§8.2.2.2): position = rotation + maskRotationOffset + thirdTerm, where the
 * third term is the static PIT-to-PSH offset when the PIT loop is not in use, or the
 * PIT-loop correction offset once the PIT loop is running. The PIT loop is taken to
 * be "in use" from the first updatePitCorrectionOffset until restartTracking.
 *
 * The actor submits HCD commands directly to the HCD CommandService (internal moves,
 * not CSW commands to the assembly, so they do not touch the assembly command-state
 * machine). It reports slew/tracking state changes back to the handler via the
 * [[Config]] callbacks, which update the published status event.
 */
object KMirrorTrackingControlActor:

  sealed trait Command
  /** Handler -> actor: the resolved HCD CommandService (sent when the HCD connects). */
  final case class HcdReady(cs: CommandService) extends Command
  /** Handler -> actor: operating-mode change from setMode. */
  final case class SetMode(mode: String) extends Command
  /** Handler -> actor: updated static PIT-to-PSH offset (deg) from updatePitToPshOffset. */
  final case class SetPitToPshOffset(deg: Double) extends Command
  /** Handler -> actor: PIT-loop correction offset (deg) from updatePitCorrectionOffset
   *  (arrives ~every 10 s while the PIT loop runs; the first one activates the PIT term). */
  final case class UpdatePitCorrection(deg: Double) extends Command
  /** Handler -> actor: restartTracking — continue tracking without the PIT loop. */
  case object RestartTracking extends Command
  /** Handler -> actor: a `stop` arrived; halt any in-flight slew move and suspend the
   *  slew loop until the next setMode. */
  case object StopSlewing extends Command
  /** Handler -> actor: latest achieved axis position (deg) for tracking convergence. */
  final case class AxisPosition(deg: Double) extends Command

  // internal
  private final case class Tcs(rotationDeg: Double, rateDegPerSec: Double, validTime: TAITime) extends Command
  private final case class MoveDone(resp: SubmitResponse, demand: Double) extends Command
  private final case class MoveFailed(ex: Throwable, demand: Double) extends Command

  final case class Config(
      assemblyPrefix: Prefix,
      galilChannel: String,
      countsPerDegree: Double,
      inPositionThresholdDeg: Double,
      trackingThresholdDeg: Double,
      maskRotationOffsetDeg: Double,
      moveTimeout: Timeout,
      eventService: EventService,
      log: Logger,
      reportSlewState: String => Unit,
      reportTrackingState: String => Unit
  )

  def apply(cfg: Config): Behavior[Command] =
    Behaviors.setup { ctx =>
      given ExecutionContext = ctx.executionContext

      // Subscribe to the TCS PupilRotation event; adapt each event to a Tcs message.
      val subscription = cfg.eventService.defaultSubscriber.subscribeCallback(
        Set(TcsPupilRotation.eventKey),
        (ev: Event) =>
          ev match
            case se: SystemEvent if se.contains(TcsPupilRotation.rotationKey) =>
              val rot = se(TcsPupilRotation.rotationKey).head
              val rate =
                if se.contains(TcsPupilRotation.rotationRateKey) then se(TcsPupilRotation.rotationRateKey).head else 0.0
              val vt =
                if se.contains(TcsPupilRotation.validTimeKey) then se(TcsPupilRotation.validTimeKey).head
                else TAITime.now()
              ctx.self ! Tcs(rot, rate, vt)
            case _ => ()
      )

      val publisher = cfg.eventService.defaultPublisher

      var mode: String                  = "MANUAL"
      var cs: Option[CommandService]    = None
      var pitToPsh: Double              = 0.0
      // slewing
      var lastDemand: Option[Double]    = None
      var suspended: Boolean            = false
      var reportedSlew: String          = "NOT_SLEWING"
      // tracking
      var trackingState: String         = "NOT_TRACKING"
      var reportedTracking: String      = "NOT_TRACKING"
      var pitActive: Boolean            = false
      var pitCorrection: Double         = 0.0
      var lastPosition: Double          = 0.0
      var currentDemand: Option[Double] = None

      def reportSlew(s: String): Unit =
        if s != reportedSlew then
          reportedSlew = s
          cfg.log.info(s"${cfg.assemblyPrefix}: slewModeState -> $s")
          cfg.reportSlewState(s)

      def reportTrack(s: String): Unit =
        if s != reportedTracking then
          reportedTracking = s
          cfg.log.info(s"${cfg.assemblyPrefix}: trackingModeState -> $s")
          cfg.reportTrackingState(s)

      // Third demand term (§8.2.2.2): PIT-loop correction once the loop is in use,
      // else the static PIT-to-PSH offset.
      def thirdTerm: Double = if pitActive then pitCorrection else pitToPsh
      def demandFor(rotationDeg: Double): Double = rotationDeg + cfg.maskRotationOffsetDeg + thirdTerm

      def stopAxis(): Unit =
        cs.foreach { svc =>
          val setup = Setup(cfg.assemblyPrefix, Hcd.StopAxisCommand.commandName, None).madd(
            Hcd.StopAxisCommand.axisKey.set(Choice(cfg.galilChannel))
          )
          svc.submitAndWait(setup)(cfg.moveTimeout).onComplete(_ => ())
        }

      // --- SLEWING: single positionAxis (degrees -> COUNTS) to pre-stage ---
      def submitSlew(demand: Double): Unit =
        cs match
          case Some(svc) =>
            val target = Math.round(demand * cfg.countsPerDegree).toFloat
            val setup = Setup(cfg.assemblyPrefix, Hcd.PositionAxisCommand.commandName, None).madd(
              Hcd.PositionAxisCommand.axisKey.set(Choice(cfg.galilChannel)),
              Hcd.PositionAxisCommand.targetKey.set(target)
            )
            lastDemand = Some(demand)
            cfg.log.info(s"${cfg.assemblyPrefix}: slew -> positionAxis target $demand deg")
            ctx.pipeToSelf(svc.submitAndWait(setup)(cfg.moveTimeout)) {
              case Success(r) => MoveDone(r, demand)
              case Failure(e) => MoveFailed(e, demand)
            }
          case None =>
            cfg.log.warn(s"${cfg.assemblyPrefix}: TrackingControl cannot slew; HCD not ready")

      // --- TRACKING: stream trackAxis (DEGREES + deg/sec; HCD converts/owns PVT) ---
      def submitTrack(posDeg: Double, rateDegPerSec: Double, validTime: TAITime): Unit =
        cs match
          case Some(svc) =>
            val setup = Setup(cfg.assemblyPrefix, Hcd.TrackAxisCommand.commandName, None).madd(
              Hcd.TrackAxisCommand.axisKey.set(Choice(cfg.galilChannel)),
              Hcd.TrackAxisCommand.positionKey.set(posDeg.toFloat),
              Hcd.TrackAxisCommand.rateKey.set(rateDegPerSec.toFloat),
              Hcd.TrackAxisCommand.validTimeKey.set(validTime)
            )
            svc.submitAndWait(setup)(cfg.moveTimeout).onComplete {
              case Success(_: Completed) => ()
              case Success(other)        => cfg.log.warn(s"${cfg.assemblyPrefix}: trackAxis response: $other")
              case Failure(e)            => cfg.log.error(s"${cfg.assemblyPrefix}: trackAxis failed: ${e.getMessage}")
            }
          case None =>
            cfg.log.warn(s"${cfg.assemblyPrefix}: TrackingControl cannot track; HCD not ready")

      def publishTrackingMetrics(demand: Double): Unit =
        val err = demand - lastPosition
        val ev = SystemEvent(cfg.assemblyPrefix, KM.TrackingMetricsEvent.eventKey.eventName).madd(
          KM.TrackingMetricsEvent.trackingErrorKey.set(err.toFloat),
          KM.TrackingMetricsEvent.withinThresholdKey.set(Math.abs(err) <= cfg.trackingThresholdDeg)
        )
        publisher.publish(ev)

      def publishTrackingError(pos: Double, demand: Double): Unit =
        val ev = SystemEvent(cfg.assemblyPrefix, KM.TrackingErrorEvent.eventKey.eventName).madd(
          KM.TrackingErrorEvent.positionKey.set(pos.toFloat),
          KM.TrackingErrorEvent.demandKey.set(demand.toFloat),
          KM.TrackingErrorEvent.thresholdKey.set(cfg.trackingThresholdDeg.toFloat)
        )
        publisher.publish(ev)

      // Next tracking state from convergence (SDD Figure 8-5). TRACKING_ERROR is
      // entered only by falling out of a Converged state; the PIT suffix follows
      // pitActive.
      def nextTrackingState(converged: Boolean): String =
        val pitSuffix = if pitActive then "_WITH_PIT" else ""
        if converged then "CONVERGED" + pitSuffix
        else
          trackingState match
            case "CONVERGED" | "CONVERGED_WITH_PIT" | "TRACKING_ERROR" => "TRACKING_ERROR"
            case _                                                     => "NOT_CONVERGED" + pitSuffix

      def updateTrackingState(): Unit =
        if mode == "TRACKING" then
          currentDemand.foreach { d =>
            val converged = Math.abs(lastPosition - d) <= cfg.trackingThresholdDeg
            val next      = nextTrackingState(converged)
            if next != trackingState then
              if next == "TRACKING_ERROR" then publishTrackingError(lastPosition, d)
              trackingState = next
              reportTrack(next)
          }

      Behaviors
        .receiveMessage[Command] {
          case HcdReady(svc) =>
            cs = Some(svc)
            Behaviors.same

          case SetPitToPshOffset(deg) =>
            pitToPsh = deg
            Behaviors.same

          case UpdatePitCorrection(deg) =>
            pitCorrection = deg
            if mode == "TRACKING" && !pitActive then
              pitActive = true
              trackingState = "NOT_CONVERGED_WITH_PIT"
              reportTrack(trackingState)
            Behaviors.same

          case RestartTracking =>
            if mode == "TRACKING" && pitActive then
              pitActive = false
              trackingState = "NOT_CONVERGED"
              reportTrack(trackingState)
            Behaviors.same

          case AxisPosition(deg) =>
            lastPosition = deg
            updateTrackingState()
            Behaviors.same

          case SetMode(m) =>
            mode = m
            suspended = false
            lastDemand = None
            currentDemand = None
            m match
              case "SLEWING" =>
                trackingState = "NOT_TRACKING"
                pitActive = false
                reportTrack("NOT_TRACKING")
                reportSlew("SLEWING")
              case "TRACKING" =>
                pitActive = false
                reportSlew("NOT_SLEWING")
                trackingState = "NOT_CONVERGED"
                reportTrack("NOT_CONVERGED")
              case _ =>
                pitActive = false
                stopAxis()
                trackingState = "NOT_TRACKING"
                reportSlew("NOT_SLEWING")
                reportTrack("NOT_TRACKING")
            Behaviors.same

          case StopSlewing =>
            // Suspend the slew/track loop so no further HCD commands are issued. The
            // axis halt is performed by the assembly's super.runStop() (a single HCD
            // stopAxis). This previously ALSO called stopAxis(), issuing a second
            // concurrent stopAxis on the same channel that raced the primary stop and
            // could starve/error under load — the sole residual after the S84 HCD
            // thread-reuse fix. setMode is the clean way back (clears suspended).
            suspended = true
            Behaviors.same

          case Tcs(rotationDeg, rateDegPerSec, validTime) =>
            if mode == "SLEWING" && !suspended then
              val demand = demandFor(rotationDeg)
              val needMove = lastDemand match
                case Some(d) => Math.abs(demand - d) > cfg.inPositionThresholdDeg
                case None    => true
              if needMove then
                reportSlew("SLEWING")
                submitSlew(demand)
            else if mode == "TRACKING" then
              val demand = demandFor(rotationDeg)
              currentDemand = Some(demand)
              submitTrack(demand, rateDegPerSec, validTime)
              publishTrackingMetrics(demand)
              updateTrackingState()
            Behaviors.same

          case MoveDone(resp, demand) =>
            if mode == "SLEWING" then
              resp match
                case _: Completed =>
                  if lastDemand.contains(demand) then reportSlew("SLEW_COMPLETE")
                case other =>
                  cfg.log.warn(s"${cfg.assemblyPrefix}: slew positionAxis non-complete: $other")
            Behaviors.same

          case MoveFailed(ex, _) =>
            cfg.log.error(s"${cfg.assemblyPrefix}: slew positionAxis failed: ${ex.getMessage}")
            Behaviors.same
        }
        .receiveSignal { case (_, PostStop) =>
          subscription.unsubscribe()
          Behaviors.same
        }
    }
