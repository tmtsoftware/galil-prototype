package aps.ics.assembly.steeringbeamsplitter

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
import aps.ics.assembly.icd.SteeringBeamSplitterStageKeys.`ICS.FOC.SteeringBeamSplitterStage` as SBS

import scala.concurrent.Future

/**
 * APS FO&C Steering Beam Splitter Stage Assembly
 * (APS.ICS.FOC.SteeringBeamSplitterStage), SDD §6.2.
 *
 * Two LINEAR axes, X and Y, on Galil controller 2 (channels B and C — the same
 * HCD the Insertion Stage uses, so no new simulator is needed). The common base
 * supplies configure / home / moveToDefaultPosition / stop and all lifecycle,
 * error-recovery and telemetry plumbing; the only assembly-specific command is
 * positionBeamSplitter, which drives both axes.
 *
 * Note on configure/stop: the ICD gives them an `axis` choice (X Stage / Y Stage)
 * for per-axis engineering use, but the common base operates on the assembly as a
 * unit (all axes). The axis choice is currently accepted and ignored; true
 * per-axis targeting can be added later if Scott wants it for debugging.
 */
class SteeringBeamSplitterStageHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String       = "SteeringBeamSplitterStage.conf"
  override protected def axisConfigKeys: List[String]  = List("xStage", "yStage")

  private def axisByName(n: String): Option[AxisConfig] = axes.find(_.name == n)

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "positionBeamSplitter" =>
        if s.exists(SBS.PositionBeamSplitterCommand.positionMethodKey)
          && s.exists(SBS.PositionBeamSplitterCommand.xValueKey)
          && s.exists(SBS.PositionBeamSplitterCommand.yValueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(
          "positionBeamSplitter requires positionMethod, xValue and yValue"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    (s.commandName.name, axisByName("xStage"), axisByName("yStage")) match
      case ("positionBeamSplitter", Some(ax), Some(ay)) =>
        val method = s(SBS.PositionBeamSplitterCommand.positionMethodKey).head.name
        val xv     = s(SBS.PositionBeamSplitterCommand.xValueKey).head.toDouble
        val yv     = s(SBS.PositionBeamSplitterCommand.yValueKey).head.toDouble
        // Resolve to absolute targets NOW (both axes are idle at intake, so the
        // current positions are accurate) so a recovery resend repeats the exact
        // same demand. RELATIVE -> current + delta, per axis; both go out as
        // absolute positionAxis and the HCD enforces soft limits on each target.
        val xt = if method == "ABSOLUTE" then xv else currentPositionMm(ax) + xv
        val yt = if method == "ABSOLUTE" then yv else currentPositionMm(ay) + yv
        val targets = Map(ax.name -> xt, ay.name -> yt)
        log.info(s"$assemblyPrefix: positionBeamSplitter $method x=$xv y=$yv -> targets x=$xt y=$yt mm")
        () => submitAllAxes(a => positionAxisMm(a, targets(a.name)))
      case ("positionBeamSplitter", _, _) =>
        () => Future.successful(Error(runId, s"$assemblyPrefix is missing its xStage/yStage axis config"))
      case (other, _, _) =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  // ---- telemetry (SDD events: status + xAxisStatus + yAxisStatus) --------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishXAxis()
    publishYAxis()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, SBS.StatusEvent.eventKey.eventName).madd(
      SBS.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      SBS.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      SBS.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishXAxis(): Unit =
    axisByName("xStage").foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      eventService.defaultPublisher.publish(
        SystemEvent(assemblyPrefix, SBS.XAxisStatusEvent.eventKey.eventName).madd(
          SBS.XAxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
          SBS.XAxisStatusEvent.positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
          SBS.XAxisStatusEvent.velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
          SBS.XAxisStatusEvent.indexedKey.set(snap.homed),
          SBS.XAxisStatusEvent.inPositionKey.set(snap.inPosition)
        )
      )
    }

  private def publishYAxis(): Unit =
    axisByName("yStage").foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      eventService.defaultPublisher.publish(
        SystemEvent(assemblyPrefix, SBS.YAxisStatusEvent.eventKey.eventName).madd(
          SBS.YAxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
          SBS.YAxisStatusEvent.positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
          SBS.YAxisStatusEvent.velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
          SBS.YAxisStatusEvent.indexedKey.set(snap.homed),
          SBS.YAxisStatusEvent.inPositionKey.set(snap.inPosition)
        )
      )
    }

/** Start the assembly from a container config file (default below). */
object SteeringBeamSplitterStageApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("SteeringBeamSplitterStageContainer.conf")
    ContainerCmd.start("SteeringBeamSplitterStage", Subsystem.APS, args, Some(defaultConfig))
