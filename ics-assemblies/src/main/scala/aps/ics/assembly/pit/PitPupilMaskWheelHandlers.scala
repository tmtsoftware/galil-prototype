package aps.ics.assembly.pit

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.command.client.messages.TopLevelActorMessage
import csw.params.core.generics.GChoiceKey
import csw.params.core.models.Choice
import csw.params.events.SystemEvent
import csw.prefix.models.Subsystem

import aps.ics.assembly.common.{AxisSnapshot, PupilMaskWheelAssemblyHandlers}
import aps.ics.assembly.icd.PitPupilMaskWheelKeys.`ICS.PIT.PupilMaskWheel` as PMW

/**
 * APS PIT Pupil Mask Wheel Assembly (APS.ICS.PIT.PupilMaskWheel), SDD §7 (Wheel
 * Assemblies).
 *
 * A single ROTATING axis ("pupilMaskWheel") on Galil controller 1, channel E (SDD
 * Figure 2-2; HCD GalilHcdConfig-APS-1.conf axis E, "pitPupilMaskWheel"). The
 * pupil-mask wheel base (PupilMaskWheelAssemblyHandlers) supplies selectPupilMask,
 * the engineering commandDetent, the detentState telemetry decode, and (via
 * WheelAssemblyHandlers / MotionAssemblyHandlers) all the common motion machinery.
 *
 * This class supplies only the PIT specifics: the generated pupilMask choice key
 * (two masks: PH-1-1, Clear) and telemetry publication using this component's own
 * event keys (status + axisStatus, the latter carrying the detentState field).
 * Structurally identical to PshPupilMaskWheelHandlers; the differences are the
 * prefix, the bound controller/channel, and the mask choice set.
 */
class PitPupilMaskWheelHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends PupilMaskWheelAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "PitPupilMaskWheel.conf"
  override protected def axisConfigKeys: List[String] = List("pupilMaskWheel")

  override protected def opticKey: GChoiceKey = PMW.SelectPupilMaskCommand.pupilMaskKey

  // ---- telemetry (SDD events: status + axisStatus, incl. detentState) ------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxisStatus()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, PMW.StatusEvent.eventKey.eventName).madd(
      PMW.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      PMW.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      PMW.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishAxisStatus(): Unit =
    axes.headOption.foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      val cpd  = a.countsPerDegree
      val velDegPerSec = if cpd != 0.0 then (snap.velocityCounts / cpd).toFloat else 0.0f
      val ev = SystemEvent(assemblyPrefix, PMW.AxisStatusEvent.eventKey.eventName).madd(
        PMW.AxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
        PMW.AxisStatusEvent.positionKey.set(snap.angularPositionDeg.toFloat),
        PMW.AxisStatusEvent.velocityKey.set(velDegPerSec),
        PMW.AxisStatusEvent.indexedKey.set(snap.homed),
        PMW.AxisStatusEvent.inPositionKey.set(snap.inPosition),
        PMW.AxisStatusEvent.wheelPositionNumKey.set(snap.wheelPositionNum),
        PMW.AxisStatusEvent.detentStateKey.set(Choice(currentDetentState))
      )
      eventService.defaultPublisher.publish(ev)
    }

/** Start the assembly from a container config file (default below). */
object PitPupilMaskWheelApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("PitPupilMaskWheelContainer.conf")
    ContainerCmd.start("PitPupilMaskWheel", Subsystem.APS, args, Some(defaultConfig))
