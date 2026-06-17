package aps.ics.assembly.calibrationsourcestage

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
import aps.ics.assembly.icd.CalibrationSourceStageKeys.`ICS.FOC.CalibrationSourceStage` as CSS

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/**
 * APS FO&C Calibration Source Stage Assembly
 * (APS.ICS.FOC.CalibrationSourceStage), SDD §6.3.
 *
 * A single LINEAR axis ("stage") that positions one of several optics/masks onto
 * the optical axis, plus an internal calibration light source.
 *
 * SCOPE (this cut): MOTION ONLY. Per SDD Figure 2-2, the stage motor is driven by
 * Galil controller 2 (solid line, channel F), while the light-source control —
 * source on/off DIO, output-select DIO, and source-voltage AO — lives on the
 * controller-3 GPIO (dashed line). The controller-3 RIO path is NOT wired yet, so
 * the light commands here are STUBS: they validate, update the assembly's
 * commanded light state for telemetry, and return Completed without performing any
 * HCD I/O. The light-source binding (HCD, addresses, max voltage) is carried in
 * config so the future RIO work has a single place to read it from.
 *
 * Motion commands (fully implemented, mapped to HCD positionAxis):
 *   setOptic                    optic   -> slot -> mm
 *   setSlot                     slot    -> mm
 *   setPosition                 absolute/relative mm move
 *   setOpticAndSourceIntensity  optic   -> slot -> mm (motion) + light (STUB)
 *
 * Light commands (STUB — return Completed, no controller-3 I/O):
 *   setSourceIntensity
 *   (and the light portion of setOpticAndSourceIntensity)
 *
 * The common base supplies configure / home / moveToDefaultPosition / stop and all
 * lifecycle, error-recovery and motion telemetry plumbing.
 */
class CalibrationSourceStageHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends StageAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String      = "CalibrationSourceStage.conf"
  override protected def axisConfigKeys: List[String] = List("stage")

  // ---- assembly-specific config (SDD Table 6-9) --------------------------

  // optic choice name -> stage slot number (1..5)
  private var opticSlots: Map[String, Int] = Map.empty
  // stage slot number -> position in mm relative to home zero
  private var slotPositions: Map[Int, Double] = Map.empty

  // Light-source binding — SPECIFIED, NOT YET WIRED (controller-3 GPIO/RIO,
  // SDD Fig 2-2). Recorded for the future RIO work; unused by the motion path.
  private var lightSourceHcd: String         = ""
  private var lightSourceMaxVoltage: Double  = 0.0
  private var lightOnOffAddress: Int         = -1
  private var lightOutputSelectAddress: Int  = -1
  private var lightVoltageAddress: Int       = -1

  // Commanded light state, reflected in the internalLightStatus event. This is a
  // STUB source: it mirrors what was last commanded, NOT a value read back from
  // the controller-3 InputOutputState (SDD §6.3.3.2). When RIO support lands,
  // replace this with a subscription to the light HCD's InputOutputState and
  // derive lightOn/lightIntensity from digitalOutputs/analogInputs.
  @volatile private var commandedLightOn: Boolean       = false
  @volatile private var commandedIntensityPct: Double   = 0.0

  override def initialize(): Unit =
    super.initialize()
    val c = componentConfig

    if c.hasPath("opticSlots") then
      val os = c.getConfig("opticSlots")
      opticSlots = os.root().keySet().asScala.map(k => k -> os.getInt(k)).toMap

    if c.hasPath("slotPositions") then
      val sp = c.getConfig("slotPositions")
      slotPositions = sp.root().keySet().asScala
        .flatMap(k => k.toIntOption.map(n => n -> sp.getDouble(k)))
        .toMap

    if c.hasPath("lightSource") then
      val ls = c.getConfig("lightSource")
      if ls.hasPath("hcd") then lightSourceHcd = ls.getString("hcd")
      if ls.hasPath("maxVoltage") then lightSourceMaxVoltage = ls.getDouble("maxVoltage")
      if ls.hasPath("onOffAddress") then lightOnOffAddress = ls.getInt("onOffAddress")
      if ls.hasPath("outputSelectAddress") then lightOutputSelectAddress = ls.getInt("outputSelectAddress")
      if ls.hasPath("voltageAddress") then lightVoltageAddress = ls.getInt("voltageAddress")

    log.info(s"$assemblyPrefix: opticSlots=$opticSlots, slotPositions=$slotPositions")
    log.info(s"$assemblyPrefix: light source STUB — hcd=$lightSourceHcd maxV=$lightSourceMaxVoltage " +
      s"onOff@$lightOnOffAddress select@$lightOutputSelectAddress voltage@$lightVoltageAddress (no I/O performed)")

  private def stageAxis: Option[AxisConfig] = axes.headOption

  /** Resolve an optic choice to its absolute stage position in mm. */
  private def opticPositionMm(optic: String): Option[Double] =
    opticSlots.get(optic).flatMap(slotPositions.get)

  // ---- assembly-specific command validation ------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "setOpticAndSourceIntensity" =>
        if s.exists(CSS.SetOpticAndSourceIntensityCommand.opticKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("setOpticAndSourceIntensity requires optic"))
      case "setOptic" =>
        if s.exists(CSS.SetOpticCommand.opticKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("setOptic requires optic"))
      case "setSlot" =>
        if s.exists(CSS.SetSlotCommand.slotNumberKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("setSlot requires slotNumber"))
      case "setPosition" =>
        if s.exists(CSS.SetPositionCommand.positioningMethodKey) && s.exists(CSS.SetPositionCommand.positionValueKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("setPosition requires positioningMethod and positionValue"))
      case "setSourceIntensity" =>
        if s.exists(CSS.SetSourceIntensityCommand.sourceIntensityKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("setSourceIntensity requires sourceIntensity"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- assembly-specific command handling --------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    stageAxis match
      case None =>
        () => Future.successful(Error(runId, s"$assemblyPrefix has no configured stage axis"))
      case Some(a) =>
        s.commandName.name match

          case "setOptic" =>
            val optic = s(CSS.SetOpticCommand.opticKey).head.name
            opticPositionMm(optic) match
              case Some(mm) =>
                log.info(s"$assemblyPrefix: setOptic $optic -> ${opticSlots(optic)} -> $mm mm")
                () => positionAxisMm(a, mm)
              case None =>
                () => Future.successful(Error(runId, s"no slot/position mapping for optic '$optic'"))

          case "setSlot" =>
            val slot = s(CSS.SetSlotCommand.slotNumberKey).head.name.toInt
            slotPositions.get(slot) match
              case Some(mm) =>
                log.info(s"$assemblyPrefix: setSlot $slot -> $mm mm")
                () => positionAxisMm(a, mm)
              case None =>
                () => Future.successful(Error(runId, s"no position mapping for slot $slot"))

          case "setPosition" =>
            val method = s(CSS.SetPositionCommand.positioningMethodKey).head.name
            val value  = s(CSS.SetPositionCommand.positionValueKey).head.toDouble
            // Resolve to an absolute target at intake (axis is idle, position is
            // settled) so a recovery resend repeats the same demand.
            val targetMm = if method == "ABSOLUTE" then value else currentPositionMm(a) + value
            log.info(s"$assemblyPrefix: setPosition $method $value mm -> target $targetMm mm")
            () => positionAxisMm(a, targetMm)

          case "setOpticAndSourceIntensity" =>
            val optic = s(CSS.SetOpticAndSourceIntensityCommand.opticKey).head.name
            // Light intent (STUB): the source is on only for CALIBRATION_SOURCE;
            // any other optic turns it off (per ICD command description).
            val on        = optic == "CALIBRATION_SOURCE"
            val intensity =
              if on && s.exists(CSS.SetOpticAndSourceIntensityCommand.sourceIntensityKey)
              then s(CSS.SetOpticAndSourceIntensityCommand.sourceIntensityKey).head.toDouble
              else 0.0
            opticPositionMm(optic) match
              case Some(mm) =>
                log.info(s"$assemblyPrefix: setOpticAndSourceIntensity $optic -> $mm mm; " +
                  s"light STUB on=$on intensity=$intensity% (no controller-3 I/O)")
                () => positionAxisMm(a, mm).map { resp =>
                  // Apply the light stub only if the motion completed.
                  if resp.isInstanceOf[Completed] then applyLightStub(on, intensity)
                  resp
                }
              case None =>
                () => Future.successful(Error(runId, s"no slot/position mapping for optic '$optic'"))

          case "setSourceIntensity" =>
            // STUB: no motion, no controller-3 I/O. Reflect the commanded
            // intensity and derive on/off from it (>0 on, 0 off), then complete.
            val intensity = s(CSS.SetSourceIntensityCommand.sourceIntensityKey).head.toDouble
            log.info(s"$assemblyPrefix: setSourceIntensity STUB intensity=$intensity% (no controller-3 I/O)")
            () =>
              applyLightStub(intensity > 0.0, intensity)
              Future.successful(Completed(runId))

          case other =>
            () => Future.successful(Error(runId, s"unsupported command: $other"))

  /** Update the commanded light state reflected in internalLightStatus. STUB
   *  bookkeeping only — performs no HCD I/O. */
  private def applyLightStub(on: Boolean, intensityPct: Double): Unit =
    commandedLightOn      = on
    commandedIntensityPct = if on then intensityPct else 0.0

  // ---- telemetry (SDD events: status + axisStatus + internalLightStatus) --

  override protected def publishTelemetry(): Unit =
    publishStatus()
    publishAxisStatus()
    publishInternalLightStatus()

  private def publishStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, CSS.StatusEvent.eventKey.eventName).madd(
      CSS.StatusEvent.assemblyStateKey.set(Choice(operationalState.choice)),
      CSS.StatusEvent.hcdStateKey.set(Choice(hcdStateChoice)),
      CSS.StatusEvent.commandStateKey.set(Choice(commandState.choice))
    )
    eventService.defaultPublisher.publish(ev)

  private def publishAxisStatus(): Unit =
    stageAxis.foreach { a =>
      val snap = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
      eventService.defaultPublisher.publish(
        SystemEvent(assemblyPrefix, CSS.AxisStatusEvent.eventKey.eventName).madd(
          CSS.AxisStatusEvent.axisStateKey.set(Choice(snap.assemblyAxisState)),
          CSS.AxisStatusEvent.positionKey.set(a.countsToMm(snap.positionCounts).toFloat),
          CSS.AxisStatusEvent.velocityKey.set(a.countsToMm(snap.velocityCounts).toFloat),
          CSS.AxisStatusEvent.indexedKey.set(snap.homed),
          CSS.AxisStatusEvent.inPositionKey.set(snap.inPosition)
        )
      )
    }

  /** STUB: reflects the last commanded light state, NOT a controller-3 readback.
   *  Replace the source with the light HCD's InputOutputState when RIO lands. */
  private def publishInternalLightStatus(): Unit =
    eventService.defaultPublisher.publish(
      SystemEvent(assemblyPrefix, CSS.InternalLightStatusEvent.eventKey.eventName).madd(
        CSS.InternalLightStatusEvent.lightOnKey.set(Choice(if commandedLightOn then "ON" else "OFF")),
        CSS.InternalLightStatusEvent.lightIntensityKey.set(commandedIntensityPct.toFloat)
      )
    )

/** Start the assembly from a container config file (default below). */
object CalibrationSourceStageApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("CalibrationSourceStageContainer.conf")
    ContainerCmd.start("CalibrationSourceStage", Subsystem.APS, args, Some(defaultConfig))