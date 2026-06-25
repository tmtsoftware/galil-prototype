package aps.ics.assembly.foc

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.command.api.scaladsl.CommandService
import csw.command.client.messages.TopLevelActorMessage
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, ControlCommand, Setup}
import csw.params.core.models.{Choice, Id}
import csw.params.events.SystemEvent
import csw.prefix.models.Subsystem

import aps.ics.assembly.common.{AxisConfig, AxisSnapshot, MotionAssemblyHandlers}
import aps.ics.assembly.icd.FocKMirrorKeys.`ICS.FOC.KMirror` as KM
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion` as Hcd

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * APS FO&C K-Mirror Assembly (APS.ICS.FOC.KMirror), SDD §8.
 *
 * A single CONTINUOUS ROTATING axis ("kMirror") on Galil controller 3, channel A
 * (SDD Figure 2-2; HCD GalilHcdConfig-APS-3.conf axis A, "kMirror"). Unlike the §7
 * wheels (rotating but slot-based), the K-Mirror is positioned to an arbitrary
 * angle in degrees, so it extends the generic MotionAssemblyHandlers directly with
 * a degree native unit (countsPerUnit = countsPerDegree) and a continuous
 * positionAxis move, rather than the wheel/stage bases. It is the only continuous
 * rotational positioning assembly, so there is no intermediate base.
 *
 * **Phases 1-3 (this file): MANUAL + SLEWING + TRACKING.** The K-Mirror accepts the
 * common configure / home / moveToDefaultPosition / stop, the diagnostic
 * positionKMirror, setMode(MANUAL|SLEWING|TRACKING), updatePitToPshOffset,
 * updatePitCorrectionOffset and restartTracking. The Tracking Control Actor (a child
 * actor) subscribes to the TCS PupilRotation event: in SLEWING it pre-stages the
 * K-Mirror to the predicted track-start angle via positionAxis (SLEW_COMPLETE gates
 * setMode(TRACKING)); in TRACKING it streams trackAxis(position, rate, validTime) and
 * runs the trackingModeState convergence machine (incl. the PIT loop).
 *
 * Command -> HCD mapping (SDD Table 8-2):
 *   home                     -> homeAxis            (base; MANUAL only)
 *   moveToDefaultPosition    -> positionAxis        (base; MANUAL only)
 *   positionKMirror          -> positionAxis        (degrees -> counts; MANUAL only;
 *                                                    HCD applies the approach algorithm)
 *   setMode                  -> none (sets mode + notifies the Tracking Control Actor;
 *                                     TRACKING requires slewModeState=SLEW_COMPLETE)
 *   updatePitToPshOffset     -> none (static demand offset, non-PIT tracking + slew)
 *   updatePitCorrectionOffset-> none (PIT-loop correction; activates the PIT term)
 *   restartTracking          -> none (continue tracking without the PIT loop)
 *   stop                     -> stopAxis            (base; also suspends the slew loop)
 *
 * Demand (SDD §8.2.2.2) is computed in the Tracking Control Actor: rotation +
 * maskRotationOffset + (PIT-loop correction when the loop is in use, else the static
 * PIT-to-PSH offset). The PIT-loop term is omitted during SLEWING.
 *
 * Telemetry: status (assembly / hcd / command states + mode / slewModeState /
 * trackingModeState) and axisStatus. slewModeState and trackingModeState are driven
 * by the Tracking Control Actor, which also publishes trackingMetrics (each tracking
 * cycle) and trackingError (on entering the error state). startupMetrics is deferred.
 */
class FocKMirrorHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends MotionAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "FocKMirror.conf"
  override protected def axisConfigKeys: List[String] = List("kMirror")

  /** Native unit is degrees: counts-per-unit is counts-per-degree (from
   *  countsPerRevolution / 360), used to scale configAxis values in runConfigure. */
  override protected def countsPerUnit(a: AxisConfig): Double = a.countsPerDegree

  // ---- operating mode (SDD §8.2.2) ---------------------------------------
  // Default MANUAL on entering Operational (SDD §8.2.2.1). @volatile: written from
  // the setMode dispatch thunk, read from the telemetry publisher.
  @volatile private var kMirrorMode: String = "MANUAL"

  // Driven by the Tracking Control Actor; read by the telemetry publisher.
  @volatile private var slewModeState: String     = "NOT_SLEWING"
  @volatile private var trackingModeState: String = "NOT_TRACKING"

  // The Tracking Control Actor (SDD §8.6.2): a long-lived child created at startup.
  private var tracking: Option[ActorRef[KMirrorTrackingControlActor.Command]] = None

  // ---- lifecycle: spawn the Tracking Control Actor ------------------------

  override def initialize(): Unit =
    super.initialize() // loads config + populates `axes`
    axes.headOption.foreach { a =>
      def cfgDouble(path: String, dflt: Double): Double =
        if componentConfig.hasPath(path) then componentConfig.getDouble(path) else dflt
      val maskOffset    = cfgDouble("kMirror.maskRotationOffset", 0.0)
      val trackingThres = cfgDouble("kMirror.trackingInPositionThreshold", a.inPositionThresholdMm)
      val cfg = KMirrorTrackingControlActor.Config(
        assemblyPrefix = assemblyPrefix,
        galilChannel = a.galilChannel,
        countsPerDegree = a.countsPerDegree,
        inPositionThresholdDeg = a.inPositionThresholdMm, // native unit = degrees here
        trackingThresholdDeg = trackingThres,
        maskRotationOffsetDeg = maskOffset,
        moveTimeout = moveHcdTimeout(a),
        eventService = eventService,
        log = log,
        reportSlewState = { (s: String) =>
          slewModeState = s
          publishTelemetry()
        },
        reportTrackingState = { (s: String) =>
          trackingModeState = s
          publishTelemetry()
        }
      )
      tracking = Some(ctx.spawn(KMirrorTrackingControlActor(cfg), "kMirrorTrackingControl"))
      log.info(s"$assemblyPrefix: Tracking Control Actor spawned " +
        s"(maskRotationOffset=$maskOffset deg, trackingThreshold=$trackingThres deg)")
    }

  /** Hand the resolved HCD CommandService to the Tracking Control Actor so it can
   *  submit positionAxis/trackAxis. (Reuses the motion-base subscribeExtra hook.) */
  override protected def subscribeExtra(hcdPrefix: String, cs: CommandService): Unit =
    if axes.headOption.exists(_.galilHcd == hcdPrefix) then
      tracking.foreach(_ ! KMirrorTrackingControlActor.HcdReady(cs))

  /** Feed live achieved position (deg) to the Tracking Control Actor for tracking
   *  convergence detection. */
  override protected def afterAxisUpdate(a: AxisConfig, snap: AxisSnapshot): Unit =
    val deg = if a.countsPerDegree != 0.0 then snap.positionCounts / a.countsPerDegree else 0.0
    tracking.foreach(_ ! KMirrorTrackingControlActor.AxisPosition(deg))

  override def onShutdown(): Unit =
    tracking.foreach(ctx.stop)
    super.onShutdown()

  /** stop also suspends the slew loop so the actor will not re-issue on the next TCS
   *  event (setMode is the clean way back). Then the base halts the axis. */
  override protected def runStop(): Future[SubmitResponse] =
    tracking.foreach(_ ! KMirrorTrackingControlActor.StopSlewing)
    super.runStop()

  // ---- mode gating for base commands (SDD §8.2.2.3) ----------------------

  /** home / moveToDefaultPosition are rejected outside MANUAL (positionKMirror is
   *  gated in validateSpecificCommand). Everything else defers to the base gate. */
  override def validateCommand(runId: Id, cmd: ControlCommand): ValidateCommandResponse =
    val name = cmd.commandName.name
    if kMirrorMode != "MANUAL" && Set("home", "moveToDefaultPosition").contains(name) then
      Invalid(runId, CommandIssue.WrongInternalStateIssue(
        s"$name is only accepted in MANUAL mode (current mode: $kMirrorMode)"))
    else super.validateCommand(runId, cmd)

  // ---- move helpers (degrees) --------------------------------------------

  /** Backstop wait for a move: worst-case shortest-arc travel is 180 deg; allow
   *  2x the nominal traverse time plus margin, floored. */
  private def moveHcdTimeout(a: AxisConfig): Timeout =
    val v = if a.velocity > 0 then a.velocity else 1.0
    Timeout(math.max(10.0, (180.0 / v) * 2.0 + 5.0).seconds)

  /** Latest known axis angle in degrees (0.0 if no snapshot yet). Accurate at
   *  command intake because commands are accepted only while the axis is idle. */
  private def currentAngleDeg(a: AxisConfig): Double =
    latestAxis
      .get(a.name)
      .map(s => if a.countsPerDegree != 0.0 then s.positionCounts / a.countsPerDegree else 0.0)
      .getOrElse(0.0)

  /** Absolute angular move: degrees -> counts -> HCD positionAxis. The HCD applies
   *  the configured approach algorithm (forward/reverse/shortest) for the rotating
   *  axis, so the assembly hands an absolute angle and the controller chooses the
   *  arc. */
  private def positionAxisDeg(a: AxisConfig, targetDeg: Double): Future[SubmitResponse] =
    submitToHcd(
      a,
      hcdSetup(
        Hcd.PositionAxisCommand.commandName,
        Hcd.PositionAxisCommand.axisKey.set(Choice(a.galilChannel)),
        Hcd.PositionAxisCommand.targetKey.set(math.round(targetDeg * a.countsPerDegree).toFloat)
      ),
      moveHcdTimeout(a)
    )

  /** moveToDefaultPosition: absolute degree move to the axis's configured default
   *  (defaultPositionMm carries the default ANGLE in degrees for this rotating axis). */
  override protected def runMoveToDefault(): Future[SubmitResponse] =
    submitAllAxes { a => positionAxisDeg(a, a.defaultPositionMm) }

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "positionKMirror" =>
        // SDD §8.2.2.3/§8.6.1: positioning is rejected outside MANUAL mode.
        if kMirrorMode != "MANUAL" then
          Invalid(runId, CommandIssue.WrongInternalStateIssue(
            s"positionKMirror is only accepted in MANUAL mode (current mode: $kMirrorMode)"))
        else if s.exists(KM.PositionKMirrorCommand.positioningMethodKey)
          && s.exists(KM.PositionKMirrorCommand.positionValueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("positionKMirror requires positioningMethod and positionValue"))

      case "setMode" =>
        if !s.exists(KM.SetModeCommand.modeKey) then
          Invalid(runId, CommandIssue.MissingKeyIssue("setMode requires mode"))
        else
          s(KM.SetModeCommand.modeKey).head.name match
            case "MANUAL" | "SLEWING" => Accepted(runId)
            case "TRACKING" =>
              // SDD §8.2.2.3: setMode(TRACKING) is available once the slew has reached
              // the pre-staged angle (SLEW_COMPLETE), to avoid a large jump at track start.
              if slewModeState == "SLEW_COMPLETE" then Accepted(runId)
              else
                Invalid(runId, CommandIssue.WrongInternalStateIssue(
                  s"setMode(TRACKING) requires slewModeState=SLEW_COMPLETE (current: $slewModeState); " +
                    "slew to the predicted angle first"))
            case other =>
              Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"setMode($other) is not a valid mode"))

      case "updatePitToPshOffset" =>
        if s.exists(KM.UpdatePitToPshOffsetCommand.pitToPshRotationOffsetKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("updatePitToPshOffset requires pitToPshRotationOffset"))

      case "updatePitCorrectionOffset" =>
        if s.exists(KM.UpdatePitCorrectionOffsetCommand.pitCorrectionOffsetKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("updatePitCorrectionOffset requires pitCorrectionOffset"))

      case "restartTracking" =>
        if kMirrorMode == "TRACKING" then Accepted(runId)
        else
          Invalid(runId, CommandIssue.WrongInternalStateIssue(
            s"restartTracking is only accepted in TRACKING mode (current mode: $kMirrorMode)"))

      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    s.commandName.name match
      case "setMode" =>
        // No HCD command: setMode sets the operating mode and notifies the Tracking
        // Control Actor, which acts on the new mode (idle in MANUAL, slewing on TCS
        // events in SLEWING). On entering MANUAL the actor reports NOT_SLEWING.
        val mode = s(KM.SetModeCommand.modeKey).head.name
        () =>
          kMirrorMode = mode
          tracking.foreach(_ ! KMirrorTrackingControlActor.SetMode(mode))
          log.info(s"$assemblyPrefix: setMode -> $mode")
          publishTelemetry()
          Future.successful(Completed(runId))

      case "updatePitToPshOffset" =>
        // No HCD command: store the static PIT-to-PSH offset in the actor for use in
        // the slewing (and non-PIT tracking) demand.
        val deg = s(KM.UpdatePitToPshOffsetCommand.pitToPshRotationOffsetKey).head.toDouble
        () =>
          tracking.foreach(_ ! KMirrorTrackingControlActor.SetPitToPshOffset(deg))
          log.info(s"$assemblyPrefix: updatePitToPshOffset -> $deg deg")
          Future.successful(Completed(runId))

      case "updatePitCorrectionOffset" =>
        // No HCD command: the running PIT loop supplies its correction (~every 10 s);
        // the first one activates the PIT term in the tracking demand (§8.2.2.4).
        val deg = s(KM.UpdatePitCorrectionOffsetCommand.pitCorrectionOffsetKey).head.toDouble
        () =>
          tracking.foreach(_ ! KMirrorTrackingControlActor.UpdatePitCorrection(deg))
          log.info(s"$assemblyPrefix: updatePitCorrectionOffset -> $deg deg")
          Future.successful(Completed(runId))

      case "restartTracking" =>
        // No HCD command: continue tracking without the PIT loop (§8.2.2.4).
        () =>
          tracking.foreach(_ ! KMirrorTrackingControlActor.RestartTracking)
          log.info(s"$assemblyPrefix: restartTracking")
          Future.successful(Completed(runId))

      case "positionKMirror" =>
        axes.headOption match
          case None =>
            () => Future.successful(Error(runId, s"$assemblyPrefix has no configured kMirror axis"))
          case Some(a) =>
            val method = s(KM.PositionKMirrorCommand.positioningMethodKey).head.name
            val value  = s(KM.PositionKMirrorCommand.positionValueKey).head.toDouble
            // Resolve RELATIVE to an absolute target now so a recovery resend reaches
            // the same demanded angle (the axis is idle at intake, so the current
            // angle is accurate). Both go out as an absolute positionAxis; the HCD
            // applies the rotating approach algorithm to the count target.
            val targetDeg = if method == "ABSOLUTE" then value else currentAngleDeg(a) + value
            log.info(s"$assemblyPrefix: positionKMirror $method $value deg -> target $targetDeg deg")
            () => positionAxisDeg(a, targetDeg)

      case other =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  // ---- telemetry (SDD events: status + axisStatus) -----------------------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxisStatus()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, KM.StatusEvent.eventKey.eventName).madd(
      KM.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      KM.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      KM.StatusEvent.commandStateKey.set(Choice(commandState.choice)),
      KM.StatusEvent.modeKey.set(Choice(kMirrorMode)),
      KM.StatusEvent.slewModeStateKey.set(Choice(slewModeState)),
      KM.StatusEvent.trackingModeStateKey.set(Choice(trackingModeState))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishAxisStatus(): Unit =
    axes.headOption.foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      val cpd  = a.countsPerDegree
      val posDeg = if cpd != 0.0 then (snap.positionCounts / cpd).toFloat else 0.0f
      val velDeg = if cpd != 0.0 then (snap.velocityCounts / cpd).toFloat else 0.0f
      val ev = SystemEvent(assemblyPrefix, KM.AxisStatusEvent.eventKey.eventName).madd(
        KM.AxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
        KM.AxisStatusEvent.positionKey.set(posDeg),
        KM.AxisStatusEvent.velocityKey.set(velDeg),
        KM.AxisStatusEvent.indexedKey.set(snap.homed),
        KM.AxisStatusEvent.inPositionKey.set(snap.inPosition)
      )
      eventService.defaultPublisher.publish(ev)
    }

/** Start the assembly from a container config file (default below). */
object FocKMirrorApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("FocKMirrorContainer.conf")
    ContainerCmd.start("FocKMirror", Subsystem.APS, args, Some(defaultConfig))
