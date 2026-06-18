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
import aps.ics.assembly.icd.FocCollimatorUnitKeys.`ICS.FOC.CollimatorUnit` as CU

import scala.concurrent.Future

/**
 * APS FO&C Collimator Unit Assembly (APS.ICS.FOC.CollimatorUnit), SDD §6.4.
 *
 * Two LINEAR axes, Front and Rear, on Galil controller 2 (channels D and E — the
 * same HCD the Insertion Stage and Steering Beam Splitter use, so no new simulator
 * is needed). The common base supplies configure / home / moveToDefaultPosition /
 * stop and all lifecycle, error-recovery and telemetry plumbing; the
 * assembly-specific commands are changeScale, positionFrontAxis and
 * positionRearAxis.
 *
 * changeScale (SDD Table 6-13, mapped to offsetAxis): a pupil-scale adjustment
 * expressed as a percent. Each motor moves a distance proportional to that percent
 * via its own scale constant (mm per %, SDD Table 6-12):
 *   frontMove = percentChange * frontAxisScaleConstant
 *   rearMove  = percentChange * rearAxisScaleConstant
 * Like every relative demand in these assemblies, it is resolved to an absolute
 * target at intake (current + delta, per axis) and dispatched as positionAxis, so
 * a recovery resend repeats the exact same demand rather than re-deriving it from
 * live state. The HCD enforces soft limits on each absolute target.
 *
 * Note on configure/stop: the ICD gives them an `axis` choice (FRONT_MOTOR /
 * BACK_MOTOR) for per-axis engineering use, but the common base operates on the
 * assembly as a unit (all axes). The axis choice is currently accepted and ignored;
 * true per-axis targeting can be added later if it is wanted for debugging.
 */
class FocCollimatorUnitHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "CollimatorUnit.conf"
  override protected def axisConfigKeys: List[String] = List("frontAxis", "rearAxis")

  // Assembly-specific config (SDD Table 6-12): mm of motor move per 1% scale
  // change, per axis. SIMULATOR BRING-UP VALUES — not calibrated.
  private var frontAxisScaleConstant: Double = 0.0
  private var rearAxisScaleConstant: Double  = 0.0

  override def initialize(): Unit =
    super.initialize()
    val c = componentConfig
    if c.hasPath("frontAxisScaleConstant") then frontAxisScaleConstant = c.getDouble("frontAxisScaleConstant")
    if c.hasPath("rearAxisScaleConstant") then rearAxisScaleConstant = c.getDouble("rearAxisScaleConstant")
    log.info(s"$assemblyPrefix: scale constants front=$frontAxisScaleConstant mm/%, rear=$rearAxisScaleConstant mm/%")

  private def axisByName(n: String): Option[AxisConfig] = axes.find(_.name == n)

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "changeScale" =>
        if s.exists(CU.ChangeScaleCommand.percentChangeKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("changeScale requires percentChange"))
      case "positionFrontAxis" =>
        if s.exists(CU.PositionFrontAxisCommand.positioningMethodKey)
          && s.exists(CU.PositionFrontAxisCommand.positionValueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(
          "positionFrontAxis requires positioningMethod and positionValue"))
      case "positionRearAxis" =>
        if s.exists(CU.PositionRearAxisCommand.positioningMethodKey)
          && s.exists(CU.PositionRearAxisCommand.positionValueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(
          "positionRearAxis requires positioningMethod and positionValue"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    s.commandName.name match
      case "changeScale" =>
        (axisByName("frontAxis"), axisByName("rearAxis")) match
          case (Some(front), Some(rear)) =>
            val percent = s(CU.ChangeScaleCommand.percentChangeKey).head.toDouble
            // Resolve both deltas to absolute targets NOW (both axes are idle at
            // intake, so the current positions are accurate) so a recovery resend
            // repeats the exact same demand. Each axis scales the percent by its
            // own constant.
            val frontTarget = currentPositionMm(front) + percent * frontAxisScaleConstant
            val rearTarget  = currentPositionMm(rear) + percent * rearAxisScaleConstant
            val targets     = Map(front.name -> frontTarget, rear.name -> rearTarget)
            log.info(s"$assemblyPrefix: changeScale $percent% -> front $frontTarget mm, rear $rearTarget mm")
            () => submitAllAxes(a => positionAxisMm(a, targets(a.name)))
          case _ =>
            () => Future.successful(Error(runId, s"$assemblyPrefix is missing its frontAxis/rearAxis config"))

      case "positionFrontAxis" => positionOneAxis(runId, s, "frontAxis",
        CU.PositionFrontAxisCommand.positioningMethodKey, CU.PositionFrontAxisCommand.positionValueKey)

      case "positionRearAxis"  => positionOneAxis(runId, s, "rearAxis",
        CU.PositionRearAxisCommand.positioningMethodKey, CU.PositionRearAxisCommand.positionValueKey)

      case other =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  /** Shared body for positionFrontAxis / positionRearAxis: resolve the demand to
   *  an absolute target at intake and move the named axis only. */
  private def positionOneAxis(
      runId: Id,
      s: Setup,
      axisName: String,
      methodKey: csw.params.core.generics.GChoiceKey,
      valueKey: csw.params.core.generics.Key[Float]
  ): () => Future[SubmitResponse] =
    axisByName(axisName) match
      case Some(a) =>
        val method = s(methodKey).head.name
        val value  = s(valueKey).head.toDouble
        val target = if method == "ABSOLUTE" then value else currentPositionMm(a) + value
        log.info(s"$assemblyPrefix: ${s.commandName.name} $method $value mm -> target $target mm")
        () => positionAxisMm(a, target)
      case None =>
        () => Future.successful(Error(runId, s"$assemblyPrefix has no '$axisName' axis configured"))

  // ---- telemetry (SDD events: status + frontAxisStatus + rearAxisStatus) --

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishFrontAxis()
    publishRearAxis()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, CU.StatusEvent.eventKey.eventName).madd(
      CU.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      CU.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      CU.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishFrontAxis(): Unit =
    axisByName("frontAxis").foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      eventService.defaultPublisher.publish(
        SystemEvent(assemblyPrefix, CU.FrontAxisStatusEvent.eventKey.eventName).madd(
          CU.FrontAxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
          CU.FrontAxisStatusEvent.positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
          CU.FrontAxisStatusEvent.velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
          CU.FrontAxisStatusEvent.indexedKey.set(snap.homed),
          CU.FrontAxisStatusEvent.inPositionKey.set(snap.inPosition)
        )
      )
    }

  private def publishRearAxis(): Unit =
    axisByName("rearAxis").foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      eventService.defaultPublisher.publish(
        SystemEvent(assemblyPrefix, CU.RearAxisStatusEvent.eventKey.eventName).madd(
          CU.RearAxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
          CU.RearAxisStatusEvent.positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
          CU.RearAxisStatusEvent.velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
          CU.RearAxisStatusEvent.indexedKey.set(snap.homed),
          CU.RearAxisStatusEvent.inPositionKey.set(snap.inPosition)
        )
      )
    }

/** Start the assembly from a container config file (default below). */
object FocCollimatorUnitApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("CollimatorUnitContainer.conf")
    ContainerCmd.start("CollimatorUnit", Subsystem.APS, args, Some(defaultConfig))