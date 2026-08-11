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
import aps.ics.assembly.icd.StimFiberSourceStageKeys.`ICS.STIM.FiberSourceStage` as FSS

import scala.concurrent.Future

/**
 * APS Stimulus Fiber Source Stage Assembly (APS.ICS.STIM.FiberSourceStage),
 * SDD §6.10.
 *
 * Three LINEAR axes (X, Y, Z) on Galil controller 4 (channels A, B, C per SDD
 * Figure 2-2), plus a fiber light source. The common base supplies configure /
 * home / moveToDefaultPosition / stop and all lifecycle, error-recovery and motion
 * telemetry plumbing.
 *
 * Motion command (fully implemented, mapped to HCD positionAxis):
 *   positionSource(ABSOLUTE|RELATIVE, x, y, z)  — moves all three axes together.
 *
 * Light command (STUB — no controller I/O):
 *   setSourceIntensity(sourcePower ON|OFF, sourceIntensity)
 *
 * SCOPE (this cut): MOTION ONLY. Per SDD §6.10 the source uses DIO (on/off,
 * output-select) and an AO (voltage); §6.10.3.2 reads light state back from the
 * Galil HCD InputOutputState. That GPIO/RIO path is NOT wired yet (controller 4
 * I/O), so the light command here is a STUB: it validates, updates the assembly's
 * commanded light state for the internalLightStatus telemetry, and returns
 * Completed without performing any HCD I/O. The light-source binding (HCD,
 * addresses, max voltage) is carried in config so the future RIO work has a single
 * place to read it from. This mirrors the Calibration Source Stage light stub.
 */
class StimFiberSourceStageHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "FiberSourceStage.conf"
  override protected def axisConfigKeys: List[String] = List("xAxis", "yAxis", "zAxis")

  // Light-source binding — SPECIFIED, NOT YET WIRED (controller-4 GPIO/RIO,
  // SDD §6.10.2.2). Recorded for the future RIO work; unused by the motion path.
  private var lightSourceHcd: String        = ""
  private var lightSourceMaxVoltage: Double = 0.0
  private var lightOnOffAddress: Int        = -1
  private var lightOutputSelectAddress: Int = -1
  private var lightVoltageAddress: Int      = -1

  // Commanded light state, reflected in the internalLightStatus event. STUB
  // source: it mirrors what was last commanded, NOT a value read back from the
  // controller InputOutputState (SDD §6.10.3.2). When RIO support lands, replace
  // this with a subscription to the light HCD's InputOutputState and derive
  // lightOn/lightIntensity from digitalInputs/analogInputs.
  @volatile private var commandedLightOn: Boolean     = false
  @volatile private var commandedIntensityPct: Double = 0.0

  override def initialize(): Unit =
    super.initialize()
    val c = componentConfig
    if c.hasPath("lightSource") then
      val ls = c.getConfig("lightSource")
      if ls.hasPath("hcd") then lightSourceHcd = ls.getString("hcd")
      if ls.hasPath("maxVoltage") then lightSourceMaxVoltage = ls.getDouble("maxVoltage")
      if ls.hasPath("onOffAddress") then lightOnOffAddress = ls.getInt("onOffAddress")
      if ls.hasPath("outputSelectAddress") then lightOutputSelectAddress = ls.getInt("outputSelectAddress")
      if ls.hasPath("voltageAddress") then lightVoltageAddress = ls.getInt("voltageAddress")
    log.info(s"$assemblyPrefix: light source STUB — hcd=$lightSourceHcd maxV=$lightSourceMaxVoltage " +
      s"onOff@$lightOnOffAddress select@$lightOutputSelectAddress voltage@$lightVoltageAddress (no I/O performed)")

  private def axisByName(n: String): Option[AxisConfig] = axes.find(_.name == n)

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "positionSource" =>
        if s.exists(FSS.PositionSourceCommand.positioningMethodKey)
          && s.exists(FSS.PositionSourceCommand.positionValueXKey)
          && s.exists(FSS.PositionSourceCommand.positionValueYKey)
          && s.exists(FSS.PositionSourceCommand.positionValueZKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(
          "positionSource requires positioningMethod, positionValueX, positionValueY and positionValueZ"))
      case "setSourceIntensity" =>
        if s.exists(FSS.SetSourceIntensityCommand.sourcePowerKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("setSourceIntensity requires sourcePower"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    s.commandName.name match
      case "positionSource" =>
        (axisByName("xAxis"), axisByName("yAxis"), axisByName("zAxis")) match
          case (Some(x), Some(y), Some(z)) =>
            val method = s(FSS.PositionSourceCommand.positioningMethodKey).head.name
            val xv = s(FSS.PositionSourceCommand.positionValueXKey).head.toDouble
            val yv = s(FSS.PositionSourceCommand.positionValueYKey).head.toDouble
            val zv = s(FSS.PositionSourceCommand.positionValueZKey).head.toDouble
            // Resolve each axis to an absolute target at intake (all idle, so
            // current positions are accurate) so a recovery resend repeats the
            // exact same demand. RELATIVE -> current + delta, per axis.
            val targets = Map(
              x.name -> (if method == "ABSOLUTE" then xv else currentPositionMm(x) + xv),
              y.name -> (if method == "ABSOLUTE" then yv else currentPositionMm(y) + yv),
              z.name -> (if method == "ABSOLUTE" then zv else currentPositionMm(z) + zv)
            )
            log.info(s"$assemblyPrefix: positionSource $method (x=$xv, y=$yv, z=$zv) mm -> " +
              s"targets ${targets(x.name)}, ${targets(y.name)}, ${targets(z.name)} mm")
            () => submitAllAxes(a => positionAxisMm(a, targets(a.name)))
          case _ =>
            () => Future.successful(Error(runId, s"$assemblyPrefix is missing its xAxis/yAxis/zAxis config"))

      case "setSourceIntensity" =>
        // STUB: no motion, no controller I/O. Reflect the commanded power/intensity
        // for the internalLightStatus telemetry, then complete.
        val on = s(FSS.SetSourceIntensityCommand.sourcePowerKey).head.name == "ON"
        val intensity =
          if on && s.exists(FSS.SetSourceIntensityCommand.sourceIntensityKey)
          then s(FSS.SetSourceIntensityCommand.sourceIntensityKey).head.toDouble
          else 0.0
        log.info(s"$assemblyPrefix: setSourceIntensity STUB power=${if on then "ON" else "OFF"} " +
          s"intensity=$intensity% (no controller I/O)")
        () =>
          applyLightStub(on, intensity)
          Future.successful(Completed(runId))

      case other =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  /** Update the commanded light state reflected in internalLightStatus. STUB
   *  bookkeeping only — performs no HCD I/O. */
  private def applyLightStub(on: Boolean, intensityPct: Double): Unit =
    commandedLightOn      = on
    commandedIntensityPct = if on then intensityPct else 0.0

  // ---- telemetry (status + x/y/zAxisStatus + internalLightStatus) ---------

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxis("xAxis", FSS.XAxisStatusEvent.eventKey.eventName,
      FSS.XAxisStatusEvent.axisStateKey, FSS.XAxisStatusEvent.positionKey, FSS.XAxisStatusEvent.velocityKey,
      FSS.XAxisStatusEvent.indexedKey, FSS.XAxisStatusEvent.inPositionKey)
    publishAxis("yAxis", FSS.YAxisStatusEvent.eventKey.eventName,
      FSS.YAxisStatusEvent.axisStateKey, FSS.YAxisStatusEvent.positionKey, FSS.YAxisStatusEvent.velocityKey,
      FSS.YAxisStatusEvent.indexedKey, FSS.YAxisStatusEvent.inPositionKey)
    publishAxis("zAxis", FSS.ZAxisStatusEvent.eventKey.eventName,
      FSS.ZAxisStatusEvent.axisStateKey, FSS.ZAxisStatusEvent.positionKey, FSS.ZAxisStatusEvent.velocityKey,
      FSS.ZAxisStatusEvent.indexedKey, FSS.ZAxisStatusEvent.inPositionKey)
    publishInternalLightStatus()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, FSS.StatusEvent.eventKey.eventName).madd(
      FSS.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      FSS.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      FSS.StatusEvent.commandStateKey.set(Choice(commandState.choice))
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

  /** STUB: reflects the last commanded light state, NOT a controller readback.
   *  Replace the source with the light HCD's InputOutputState when RIO lands. */
  private def publishInternalLightStatus(): Unit =
    eventService.defaultPublisher.publish(
      SystemEvent(assemblyPrefix, FSS.InternalLightStatusEvent.eventKey.eventName).madd(
        FSS.InternalLightStatusEvent.lightOnKey.set(Choice(if commandedLightOn then "ON" else "OFF")),
        FSS.InternalLightStatusEvent.lightIntensityKey.set(commandedIntensityPct.toFloat)
      )
    )

/** Start the assembly from a container config file (default below). */
object StimFiberSourceStageApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("FiberSourceStageContainer.conf")
    ContainerCmd.start("FiberSourceStage", Subsystem.APS, args, Some(defaultConfig))
