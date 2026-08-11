package aps.ics.assembly.stim

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
import aps.ics.assembly.icd.StimPupilMaskStageKeys.`ICS.STIM.PupilMaskStage` as PMS

import scala.concurrent.Future

/**
 * APS Stimulus Pupil Mask Stage Assembly (APS.ICS.STIM.PupilMaskStage),
 * SDD §6.11.
 *
 * Three axes that move the mask in X, Y (LINEAR) and Phi (ROTATIONAL about the
 * optical/z axis) on Galil controller 4 (channels D, E, F per SDD Figure 2-2).
 * The common base supplies configure / home / moveToDefaultPosition / stop and all
 * lifecycle, error-recovery and telemetry plumbing. The single assembly-specific
 * command is positionMaskStage, mapped to HCD positionAxis/offsetAxis. There is no
 * PupilMaskStage-specific configuration (SDD §6.11.2.2: None).
 *
 * Phi is rotational: its axis config sets isRotational=true so the base configures
 * it via configRotatingAxis (rotation algorithm from rotationalPositioningMethod),
 * and its demand value is in degrees (countsPerMm carries counts-per-degree). The
 * base's mm<->counts path is unit-agnostic, so the rotational axis rides it
 * unchanged — the same path the K-Mirror and wheels will use.
 *
 * NOTE: this handler depends on the PROVISIONAL StimPupilMaskStageKeys (hand-
 * authored, pending an icd-db regen — see that file's header). If a regen renames
 * positionMaskStage args or the axisStatus event names, update the references here
 * to match.
 */
class StimPupilMaskStageHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "PupilMaskStage.conf"
  override protected def axisConfigKeys: List[String] = List("xAxis", "yAxis", "phiAxis")

  private def axisByName(n: String): Option[AxisConfig] = axes.find(_.name == n)

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "positionMaskStage" =>
        if s.exists(PMS.PositionMaskStageCommand.positioningMethodKey)
          && s.exists(PMS.PositionMaskStageCommand.positionValueXKey)
          && s.exists(PMS.PositionMaskStageCommand.positionValueYKey)
          && s.exists(PMS.PositionMaskStageCommand.positionValuePhiKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(
          "positionMaskStage requires positioningMethod, positionValueX, positionValueY and positionValuePhi"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    s.commandName.name match
      case "positionMaskStage" =>
        (axisByName("xAxis"), axisByName("yAxis"), axisByName("phiAxis")) match
          case (Some(x), Some(y), Some(phi)) =>
            val method = s(PMS.PositionMaskStageCommand.positioningMethodKey).head.name
            val xv   = s(PMS.PositionMaskStageCommand.positionValueXKey).head.toDouble
            val yv   = s(PMS.PositionMaskStageCommand.positionValueYKey).head.toDouble
            val phiv = s(PMS.PositionMaskStageCommand.positionValuePhiKey).head.toDouble
            // Resolve each axis to an absolute target at intake (all idle, so
            // current positions are accurate) so a recovery resend repeats the
            // exact same demand. RELATIVE -> current + delta, per axis. The phi
            // value is in degrees; the rotational axis converts via its own
            // counts-per-degree (countsPerMm) in the base.
            val targets = Map(
              x.name   -> (if method == "ABSOLUTE" then xv   else currentPositionMm(x) + xv),
              y.name   -> (if method == "ABSOLUTE" then yv   else currentPositionMm(y) + yv),
              phi.name -> (if method == "ABSOLUTE" then phiv else currentPositionMm(phi) + phiv)
            )
            log.info(s"$assemblyPrefix: positionMaskStage $method (x=$xv mm, y=$yv mm, phi=$phiv deg) -> " +
              s"targets ${targets(x.name)}, ${targets(y.name)}, ${targets(phi.name)}")
            () => submitAllAxes(a => positionAxisMm(a, targets(a.name)))
          case _ =>
            () => Future.successful(Error(runId, s"$assemblyPrefix is missing its xAxis/yAxis/phiAxis config"))
      case other =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  // ---- telemetry (status + x/y/phiAxisStatus) -----------------------------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxis("xAxis", PMS.XAxisStatusEvent.eventKey.eventName,
      PMS.XAxisStatusEvent.axisStateKey, PMS.XAxisStatusEvent.positionKey, PMS.XAxisStatusEvent.velocityKey,
      PMS.XAxisStatusEvent.indexedKey, PMS.XAxisStatusEvent.inPositionKey)
    publishAxis("yAxis", PMS.YAxisStatusEvent.eventKey.eventName,
      PMS.YAxisStatusEvent.axisStateKey, PMS.YAxisStatusEvent.positionKey, PMS.YAxisStatusEvent.velocityKey,
      PMS.YAxisStatusEvent.indexedKey, PMS.YAxisStatusEvent.inPositionKey)
    publishAxis("phiAxis", PMS.PhiAxisStatusEvent.eventKey.eventName,
      PMS.PhiAxisStatusEvent.axisStateKey, PMS.PhiAxisStatusEvent.positionKey, PMS.PhiAxisStatusEvent.velocityKey,
      PMS.PhiAxisStatusEvent.indexedKey, PMS.PhiAxisStatusEvent.inPositionKey)

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, PMS.StatusEvent.eventKey.eventName).madd(
      PMS.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      PMS.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      PMS.StatusEvent.commandStateKey.set(Choice(commandState.choice))
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
object StimPupilMaskStageApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("PupilMaskStageContainer.conf")
    ContainerCmd.start("PupilMaskStage", Subsystem.APS, args, Some(defaultConfig))
