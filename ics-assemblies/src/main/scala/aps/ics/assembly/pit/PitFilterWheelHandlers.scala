package aps.ics.assembly.pit

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

import aps.ics.assembly.common.{AxisSnapshot, WheelAssemblyHandlers}
import aps.ics.assembly.icd.PitFilterWheelKeys.`ICS.PIT.FilterWheel` as FW

import scala.jdk.CollectionConverters._

/**
 * APS PIT Filter Wheel Assembly (APS.ICS.PIT.FilterWheel), SDD §7 (Wheel Assemblies).
 *
 * A single ROTATING axis ("filterWheel") on Galil controller 1, channel F (SDD
 * Figure 2-2; HCD GalilHcdConfig-APS-1.conf axis F, "pitFilterWheel"). The common
 * wheel base (WheelAssemblyHandlers) supplies configure / home /
 * moveToDefaultPosition / stop, the engineering moves positionWheel / positionMotor,
 * and all lifecycle, error-recovery and telemetry plumbing. This class adds only
 * the PIT-specific selectFilter command, which resolves a filter NAME to a wheel
 * slot via the Wheel Position N Assignment config (SDD Table 7-1).
 *
 * Structurally identical to PshFilterWheelHandlers (same seven filters, 1..7 slots);
 * the differences are the prefix, the bound controller/channel, and the provisional
 * slot assignments in PitFilterWheel.conf.
 *
 * Phase note: the IR-sensor / photodiode GPIO reads (SDD §7.4.3.2) are not wired
 * here yet — that is the InputOutputState subscription, shared with the pupil-mask
 * detent work.
 */
class PitFilterWheelHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends WheelAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "PitFilterWheel.conf"
  override protected def axisConfigKeys: List[String] = List("filterWheel")

  // ---- selectFilter: optic name -> wheel slot ----------------------------

  override protected def selectCommandName: String = "selectFilter"

  /** filter name -> slot, from `filterWheel.positionAssignments` (slot -> name). */
  private def filterToSlot: Map[String, Int] =
    if componentConfig.hasPath("filterWheel.positionAssignments") then
      val pa = componentConfig.getConfig("filterWheel.positionAssignments")
      pa.root().keySet().asScala.flatMap { slotStr =>
        slotStr.toIntOption.map(slot => pa.getString(slotStr) -> slot)
      }.toMap
    else Map.empty

  override protected def resolveSelectSlot(s: Setup): Either[String, Int] =
    val filter = s(FW.SelectFilterCommand.filterKey).head.name
    filterToSlot.get(filter).toRight(s"filter '$filter' has no assigned wheel position")

  override protected def validateSelectCommand(runId: Id, s: Setup): ValidateCommandResponse =
    if !s.exists(FW.SelectFilterCommand.filterKey) then
      Invalid(runId, CommandIssue.MissingKeyIssue("selectFilter requires filter"))
    else
      resolveSelectSlot(s) match
        case Right(_)  => Accepted(runId)
        case Left(msg) => Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue(msg))

  // ---- telemetry (SDD events: status + axisStatus) -----------------------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxisStatus()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, FW.StatusEvent.eventKey.eventName).madd(
      FW.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      FW.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      FW.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishAxisStatus(): Unit =
    axes.headOption.foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      val cpd  = a.countsPerDegree
      val velDegPerSec = if cpd != 0.0 then (snap.velocityCounts / cpd).toFloat else 0.0f
      val ev = SystemEvent(assemblyPrefix, FW.AxisStatusEvent.eventKey.eventName).madd(
        FW.AxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
        FW.AxisStatusEvent.positionKey.set(snap.angularPositionDeg.toFloat),
        FW.AxisStatusEvent.velocityKey.set(velDegPerSec),
        FW.AxisStatusEvent.indexedKey.set(snap.homed),
        FW.AxisStatusEvent.inPositionKey.set(snap.inPosition),
        FW.AxisStatusEvent.wheelPositionNumKey.set(snap.wheelPositionNum)
      )
      eventService.defaultPublisher.publish(ev)
    }

/** Start the assembly from a container config file (default below). */
object PitFilterWheelApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("PitFilterWheelContainer.conf")
    ContainerCmd.start("PitFilterWheel", Subsystem.APS, args, Some(defaultConfig))
