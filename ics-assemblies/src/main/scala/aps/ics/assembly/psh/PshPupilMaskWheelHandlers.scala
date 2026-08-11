package aps.ics.assembly.psh

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
import aps.ics.assembly.icd.PshPupilMaskWheelKeys.`ICS.PSH.PupilMaskWheel` as PMW

/**
 * APS PSH Pupil Mask Wheel Assembly (APS.ICS.PSH.PupilMaskWheel), SDD §7 (Wheel
 * Assemblies).
 *
 * A single ROTATING axis ("pupilMaskWheel") on Galil controller 1, channel C (SDD
 * Figure 2-2; HCD GalilHcdConfig-APS-1.conf axis C, "pshPupilMaskWheel"). The
 * pupil-mask wheel base (PupilMaskWheelAssemblyHandlers) supplies selectPupilMask,
 * the engineering commandDetent, the detentState telemetry decode, and (via
 * WheelAssemblyHandlers / MotionAssemblyHandlers) configure / home /
 * moveToDefaultPosition / stop / positionWheel / positionMotor and all lifecycle,
 * error-recovery and telemetry plumbing.
 *
 * This class supplies only the PSH specifics: the generated pupilMask choice key
 * (five masks: PH-2-0, SH-0, SH-2, SH-5, Clear) and telemetry publication using
 * this component's own event keys (status + axisStatus, the latter carrying the
 * detentState field).
 */
class PshPupilMaskWheelHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends PupilMaskWheelAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "PshPupilMaskWheel.conf"
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
object PshPupilMaskWheelApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("PshPupilMaskWheelContainer.conf")
    ContainerCmd.start("PshPupilMaskWheel", Subsystem.APS, args, Some(defaultConfig))
