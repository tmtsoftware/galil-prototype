package aps.ics.assembly.insertionstage

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
import aps.ics.assembly.icd.InsertionStageKeys.`ICS.STIM.InsertionStage` as IS

import scala.concurrent.Future

/**
 * APS Stimulus Insertion Stage Assembly (APS.ICS.STIM.InsertionStage), SDD §6.9.
 * A single linear axis ("stage"). selectSource and positionStage map to HCD
 * positionAxis/offsetAxis; the common base supplies configure/home/
 * moveToDefaultPosition/stop and all lifecycle/telemetry plumbing.
 */
class InsertionStageHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String       = "InsertionStage.conf"
  override protected def axisConfigKeys: List[String]  = List("stage")

  // Assembly-specific config (SDD Table 6-24), in mm.
  private var stimulusPositionMm: Double = 0.0
  private var skyPositionMm: Double      = 0.0

  override def initialize(): Unit =
    super.initialize()
    // Read the IS-specific keys from the config the base already loaded
    // (Config Service active version or fallback) — no second load.
    val c = componentConfig
    if c.hasPath("stimulusPosition") then stimulusPositionMm = c.getDouble("stimulusPosition")
    if c.hasPath("skyPosition") then skyPositionMm = c.getDouble("skyPosition")
    log.info(s"$assemblyPrefix: stimulus=$stimulusPositionMm mm, sky=$skyPositionMm mm")

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "selectSource" =>
        if s.exists(IS.SelectSourceCommand.lightSourceKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("selectSource requires lightSource"))
      case "positionStage" =>
        if s.exists(IS.PositionStageCommand.positionMethodKey) && s.exists(IS.PositionStageCommand.valueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("positionStage requires positionMethod and value"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    val axisOpt = axes.headOption
    (s.commandName.name, axisOpt) match
      case (_, None) =>
        () => Future.successful(Error(runId, s"$assemblyPrefix has no configured stage axis"))
      case ("selectSource", Some(a)) =>
        val src = s(IS.SelectSourceCommand.lightSourceKey).head.name
        val targetMm = if src == "STIMULUS" then stimulusPositionMm else skyPositionMm
        log.info(s"$assemblyPrefix: selectSource $src -> $targetMm mm")
        () => positionAxisMm(a, targetMm)
      case ("positionStage", Some(a)) =>
        val method = s(IS.PositionStageCommand.positionMethodKey).head.name
        val value  = s(IS.PositionStageCommand.valueKey).head.toDouble
        // Resolve to an absolute target now so a recovery resend reaches the
        // same demanded position (the axis is idle at intake, so the current
        // position is accurate). RELATIVE -> current + delta; both go out as an
        // absolute positionAxis, and the HCD enforces soft limits on the target.
        val targetMm = if method == "ABSOLUTE" then value else currentPositionMm(a) + value
        log.info(s"$assemblyPrefix: positionStage $method $value mm -> target $targetMm mm")
        () => positionAxisMm(a, targetMm)
      case (other, _) =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  // ---- telemetry (SDD events: status + axisStatus) -----------------------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxisStatus()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, IS.StatusEvent.eventKey.eventName).madd(
      IS.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      IS.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      IS.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishAxisStatus(): Unit =
    axes.headOption.foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      val ev = SystemEvent(assemblyPrefix, IS.AxisStatusEvent.eventKey.eventName).madd(
        IS.AxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
        IS.AxisStatusEvent.positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
        IS.AxisStatusEvent.velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
        IS.AxisStatusEvent.indexedKey.set(snap.homed),
        IS.AxisStatusEvent.inPositionKey.set(snap.inPosition)
      )
      eventService.defaultPublisher.publish(ev)
    }

/** Start the assembly from a container config file (default below). */
object InsertionStageApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("InsertionStageContainer.conf")
    ContainerCmd.start("InsertionStage", Subsystem.APS, args, Some(defaultConfig))