package aps.ics.assembly.foc

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.command.client.messages.TopLevelActorMessage
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, Setup}
import csw.params.core.models.{Choice, Id}
import csw.params.events.SystemEvent
import csw.prefix.models.Subsystem

import aps.ics.assembly.common.{AxisConfig, AxisSnapshot, StageAssemblyHandlers}
import aps.ics.assembly.icd.FocTiltPlateKeys.`ICS.FOC.TiltPlate` as TP

import scala.concurrent.Future

/**
 * APS FO&C Tilt Plate Assembly (APS.ICS.FOC.TiltPlate), SDD §6.5.
 *
 * Two LINEAR axes, X and Y, on Galil controller 3 (channels B and C per SDD
 * Figure 2-2). The common base supplies configure / home / moveToDefaultPosition
 * / stop and all lifecycle, error-recovery and telemetry plumbing; the single
 * assembly-specific command is positionTiltPlate.
 *
 * Pupil-plane (M1) demand vs. stage motion (SDD §6.5.2.2, Table 6-15):
 *   positionTiltPlate commands a translation of the PUPIL at M1 ("translate the
 *   pupil in x and y"; SDD §3.10.4 commands it as "mm pupil motion"). The
 *   per-axis conversion factor "converts stage mm to mm at M1", i.e.
 *       m1_mm = stage_mm * factor
 *   so to realise a demanded pupil motion the stage must move
 *       stage_mm = m1_mm / factor.
 *   Each demand is resolved to an absolute STAGE target at intake (both axes are
 *   idle, so current positions are accurate) and dispatched as positionAxis, so a
 *   recovery resend repeats the exact same demand. The HCD enforces soft limits
 *   on the resulting stage target.
 *
 *   *** DIRECTION + FACTOR VALUES ARE BRING-UP PLACEHOLDERS — confirm in PR phase
 *   against RD15/the optical model (same status as the Collimator scale
 *   constants). ***
 *
 * Note on configure/stop: the ICD gives them an `axis` choice (X Stage / Y Stage)
 * for per-axis engineering use, but the common base operates on the assembly as a
 * unit (all axes); the choice is accepted and ignored. True per-axis targeting can
 * be added later if wanted for debugging.
 */
class FocTiltPlateHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "TiltPlate.conf"
  override protected def axisConfigKeys: List[String] = List("xAxis", "yAxis")

  // Assembly-specific config (SDD Table 6-15): stage->M1 conversion factor per
  // axis (m1_mm per stage_mm). BRING-UP VALUES — not calibrated.
  private var xStageToM1Factor: Double = 1.0
  private var yStageToM1Factor: Double = 1.0

  override def initialize(): Unit =
    super.initialize()
    val c = componentConfig
    if c.hasPath("xStageToM1Factor") then xStageToM1Factor = c.getDouble("xStageToM1Factor")
    if c.hasPath("yStageToM1Factor") then yStageToM1Factor = c.getDouble("yStageToM1Factor")
    log.info(s"$assemblyPrefix: stage->M1 factors x=$xStageToM1Factor y=$yStageToM1Factor (m1_mm per stage_mm)")

  private def axisByName(n: String): Option[AxisConfig] = axes.find(_.name == n)

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "positionTiltPlate" =>
        if s.exists(TP.PositionTiltPlateCommand.positioningMethodKey)
          && s.exists(TP.PositionTiltPlateCommand.xValueKey)
          && s.exists(TP.PositionTiltPlateCommand.yValueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(
          "positionTiltPlate requires positioningMethod, xValue and yValue"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    s.commandName.name match
      case "positionTiltPlate" =>
        (axisByName("xAxis"), axisByName("yAxis")) match
          case (Some(x), Some(y)) =>
            val method = s(TP.PositionTiltPlateCommand.positioningMethodKey).head.name
            val xPupil = s(TP.PositionTiltPlateCommand.xValueKey).head.toDouble
            val yPupil = s(TP.PositionTiltPlateCommand.yValueKey).head.toDouble
            // Convert pupil-plane (M1) demand -> stage motion (divide by factor),
            // then resolve to an absolute STAGE target at intake so a recovery
            // resend repeats the same demand.
            val xStage = pupilToStage(xPupil, xStageToM1Factor)
            val yStage = pupilToStage(yPupil, yStageToM1Factor)
            val xTarget = if method == "ABSOLUTE" then xStage else currentPositionMm(x) + xStage
            val yTarget = if method == "ABSOLUTE" then yStage else currentPositionMm(y) + yStage
            val targets = Map(x.name -> xTarget, y.name -> yTarget)
            log.info(s"$assemblyPrefix: positionTiltPlate $method pupil(x=$xPupil, y=$yPupil) mm " +
              s"-> stage(x=$xTarget, y=$yTarget) mm")
            () => submitAllAxes(a => positionAxisMm(a, targets(a.name)))
          case _ =>
            () => Future.successful(Error(runId, s"$assemblyPrefix is missing its xAxis/yAxis config"))
      case other =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  /** Pupil-plane (M1) mm -> stage mm. factor = m1_mm per stage_mm; guard against
   *  a zero/absent factor (treat as 1:1) so a misconfig can't divide by zero. */
  private def pupilToStage(m1Mm: Double, factor: Double): Double =
    if factor != 0.0 then m1Mm / factor else m1Mm

  // ---- telemetry (SDD events: status + xAxisStatus + yAxisStatus) ---------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxis("xAxis", TP.XAxisStatusEvent.eventKey.eventName,
      TP.XAxisStatusEvent.axisStateKey, TP.XAxisStatusEvent.positionKey, TP.XAxisStatusEvent.velocityKey,
      TP.XAxisStatusEvent.indexedKey, TP.XAxisStatusEvent.inPositionKey)
    publishAxis("yAxis", TP.YAxisStatusEvent.eventKey.eventName,
      TP.YAxisStatusEvent.axisStateKey, TP.YAxisStatusEvent.positionKey, TP.YAxisStatusEvent.velocityKey,
      TP.YAxisStatusEvent.indexedKey, TP.YAxisStatusEvent.inPositionKey)

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, TP.StatusEvent.eventKey.eventName).madd(
      TP.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      TP.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      TP.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishAxis(
      axisName: String,
      eventName: csw.params.events.EventName,
      axisStateKey: csw.params.core.generics.GChoiceKey,
      positionKey: csw.params.core.generics.Key[Float],
      velocityKey: csw.params.core.generics.Key[Float],
      indexedKey: csw.params.core.generics.Key[Boolean],
      inPositionKey: csw.params.core.generics.Key[Boolean]
  ): Unit =
    axisByName(axisName).foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      eventService.defaultPublisher.publish(
        SystemEvent(assemblyPrefix, eventName).madd(
          axisStateKey.set(Choice(snap.assemblyAxisState)),
          positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
          velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
          indexedKey.set(snap.homed),
          inPositionKey.set(snap.inPosition)
        )
      )
    }

/** Start the assembly from a container config file (default below). */
object FocTiltPlateApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("TiltPlateContainer.conf")
    ContainerCmd.start("TiltPlate", Subsystem.APS, args, Some(defaultConfig))
