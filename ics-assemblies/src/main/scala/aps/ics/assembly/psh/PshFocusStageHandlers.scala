package aps.ics.assembly.psh

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

import aps.ics.assembly.common.{AxisSnapshot, StageAssemblyHandlers}
import aps.ics.assembly.icd.PshFocusStageKeys.`ICS.PSH.FocusStage` as FS

import scala.concurrent.Future

/**
 * APS PSH Focus Stage Assembly (APS.ICS.PSH.FocusStage), SDD §6.6.
 *
 * A single LINEAR axis ("stage"). The only assembly-specific command is
 * positionFocusStage, which maps to HCD positionAxis/offsetAxis; the common base
 * supplies configure / home / moveToDefaultPosition / stop and all lifecycle,
 * error-recovery and telemetry plumbing. There is no PSH-specific configuration
 * (SDD §6.6.2.2: None).
 *
 * Per SDD Figure 2-2 the stage motor is on Galil controller 1, channel A.
 */
class PshFocusStageHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "PshFocusStage.conf"
  override protected def axisConfigKeys: List[String] = List("stage")

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "positionFocusStage" =>
        if s.exists(FS.PositionFocusStageCommand.positioningMethodKey) && s.exists(FS.PositionFocusStageCommand.valueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("positionFocusStage requires positioningMethod and value"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    val axisOpt = axes.headOption
    (s.commandName.name, axisOpt) match
      case (_, None) =>
        () => Future.successful(Error(runId, s"$assemblyPrefix has no configured stage axis"))
      case ("positionFocusStage", Some(a)) =>
        val method = s(FS.PositionFocusStageCommand.positioningMethodKey).head.name
        val value  = s(FS.PositionFocusStageCommand.valueKey).head.toDouble
        // Resolve to an absolute target now so a recovery resend reaches the same
        // demanded position (the axis is idle at intake, so the current position
        // is accurate). RELATIVE -> current + delta; both go out as an absolute
        // positionAxis, and the HCD enforces soft limits on the target.
        val targetMm = if method == "ABSOLUTE" then value else currentPositionMm(a) + value
        log.info(s"$assemblyPrefix: positionFocusStage $method $value mm -> target $targetMm mm")
        () => positionAxisMm(a, targetMm)
      case (other, _) =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  // ---- telemetry (SDD events: status + axisStatus) -----------------------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxisStatus()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, FS.StatusEvent.eventKey.eventName).madd(
      FS.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      FS.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      FS.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishAxisStatus(): Unit =
    axes.headOption.foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      val ev = SystemEvent(assemblyPrefix, FS.AxisStatusEvent.eventKey.eventName).madd(
        FS.AxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
        FS.AxisStatusEvent.positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
        FS.AxisStatusEvent.velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
        FS.AxisStatusEvent.indexedKey.set(snap.homed),
        FS.AxisStatusEvent.inPositionKey.set(snap.inPosition)
      )
      eventService.defaultPublisher.publish(ev)
    }

/** Start the assembly from a container config file (default below). */
object PshFocusStageApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("PshFocusStageContainer.conf")
    ContainerCmd.start("PshFocusStage", Subsystem.APS, args, Some(defaultConfig))
