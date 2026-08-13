package aps.ics.assembly.abe

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.pattern.after
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.logging.client.commons.LogAdminUtil
import csw.logging.models.Level
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, ControlCommand, Setup}
import csw.params.core.models.{Choice, Id}
import csw.params.events.SystemEvent
import csw.time.core.models.UTCTime

import aps.ics.assembly.icd.AbeEnclosureKeys.`ICS.ABE.Enclosure` as Keys

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

/**
 * APS-ICS ABE Enclosure assembly MOCK (self-contained), ICD section 22.
 *
 * Same rationale as [[AbeShutterHandlers]]: the ICD send-model declares
 * `setBit`/`faultReset` to ICS.HCD.GalilMotion (purge and coolant valves are
 * Galil digital outputs in the real system), but the output-bit assignments
 * are not in the model, so this mock actuates nothing: valve state is held
 * internally and the derived telemetry follows it after a fixed delay.
 *
 * Mock semantics:
 *   - `commandPurgeAir` (ON/OFF): purgeAirFlowRate becomes
 *     [[AbeEnclosureHandlers.PurgeFlowOn]] / 0.0 after the valve delay.
 *   - `commandCoolantControlValve` (ON/OFF): coolantPressure and the four
 *     per-detector coolant flow rates follow the valve state. In the ICD
 *     model but unused by the sequencer scripts today; implemented for
 *     model fidelity (full command set, per Angelic 2026-08-13).
 *   - `status` @1 Hz with plausible static bench environment values (~20 C,
 *     low humidity, healthy leak sensors). `hcdState` READY by fiat.
 *   - One command at a time (PROCESSING gates validation), matching the
 *     detector mocks' busy gating.
 *   - startupMetrics: declared in the model, not published — consistent with
 *     the assemblies' known declared-but-never-published batch (PROJECT_STATE
 *     section 10); publish it there when that batch lands.
 */
class AbeEnclosureHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx):

  import cswCtx._

  private val log                                   = loggerFactory.getLogger
  private implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val assemblyPrefix                        = componentInfo.prefix

  /** Mock actuation time for the purge / coolant valves. */
  private val ValveActuationTime: FiniteDuration = 500.millis

  /** Purge air flow when the valve is open (liters/sec, model units NoUnits). */
  private val PurgeFlowOn: Float = 30.0f

  /** Coolant pressure (bar) and per-detector flow (liters/sec) when the coolant valve is open. */
  private val CoolantPressureOn: Float = 2.0f
  private val CoolantFlowOn: Float     = 0.5f

  @volatile private var purgeAirOn: Boolean  = false
  @volatile private var coolantOn: Boolean   = false
  @volatile private var commandState: String = "IDLE"

  private var statusTimer: Option[Cancellable] = None

  // ---- Lifecycle ---------------------------------------------------------

  override def initialize(): Unit =
    // Default this component's log level to INFO at runtime; see the rationale
    // comment in MotionAssemblyHandlers.initialize() (and the underlying CSW
    // limitation analysis in GalilHcd.scala, "KNOWN CSW LIMITATION").
    LogAdminUtil.setComponentLogLevel(componentInfo.prefix, Level.INFO)
    log.info(s"$assemblyPrefix: initialize (ABE Enclosure mock, self-contained; purge OFF, coolant OFF)")
    statusTimer = Some(ctx.system.scheduler.scheduleWithFixedDelay(1.second, 1.second)(() => publishStatus()))

  override def onShutdown(): Unit =
    statusTimer.foreach(_.cancel())
    log.info(s"$assemblyPrefix: shutdown")

  override def onGoOffline(): Unit = {}
  override def onGoOnline(): Unit  = {}

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit  = {}
  override def onOperationsMode(): Unit                                  = {}
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {}

  // ---- Telemetry ---------------------------------------------------------

  private def buildStatusEvent(): SystemEvent =
    val purgeFlow    = if purgeAirOn then PurgeFlowOn else 0.0f
    val coolPressure = if coolantOn then CoolantPressureOn else 0.0f
    val coolFlow     = if coolantOn then CoolantFlowOn else 0.0f
    SystemEvent(assemblyPrefix, Keys.StatusEvent.eventKey.eventName)
      .add(Keys.StatusEvent.assemblyStateKey.set(Choice("READY")))
      .add(Keys.StatusEvent.hcdStateKey.set(Choice("READY"))) // by fiat: no HCD bound in the mock
      .add(Keys.StatusEvent.commandStateKey.set(Choice(commandState)))
      .add(Keys.StatusEvent.purgeAirFlowRateKey.set(purgeFlow))
      .add(Keys.StatusEvent.coolantPressureKey.set(coolPressure))
      .add(Keys.StatusEvent.benchCoolantLeakSensorFaultDetectionKey.set(Choice("READY")))
      .add(Keys.StatusEvent.rackToBenchCoolantLeakSensorFaultDetectionKey.set(Choice("READY")))
      .add(Keys.StatusEvent.rackCoolantLeakSensorFaultDetectionKey.set(Choice("READY")))
      .add(Keys.StatusEvent.benchCoolantLeakSensorDetectionStateKey.set(Choice("NONE")))
      .add(Keys.StatusEvent.rackToBenchCoolantLeakSensorDetectionStateKey.set(Choice("NONE")))
      .add(Keys.StatusEvent.rackCoolantLeakSensorDetectionStateKey.set(Choice("NONE")))
      .add(Keys.StatusEvent.pshCoolantFlowRateKey.set(coolFlow))
      .add(Keys.StatusEvent.pitCoolantFlowRateKey.set(coolFlow))
      .add(Keys.StatusEvent.aptCoolantFlowRateKey.set(coolFlow))
      .add(Keys.StatusEvent.lowfsCoolantFlowRateKey.set(coolFlow))
      .add(Keys.StatusEvent.shutterTemperatureKey.set(20.0f))
      .add(Keys.StatusEvent.shutterHumidityKey.set(5.0f))
      .add(Keys.StatusEvent.shutterDewPointKey.set(-5.0f))
      .add(Keys.StatusEvent.pshTemperatureKey.set(20.0f))
      .add(Keys.StatusEvent.pshHumidityKey.set(5.0f))
      .add(Keys.StatusEvent.pshDewPointKey.set(-5.0f))
      .add(Keys.StatusEvent.electronicsCabinetTemperatureKey.set(22.0f))
      .add(Keys.StatusEvent.electronicsCabinetHumidityKey.set(5.0f))
      .add(Keys.StatusEvent.electronicsCabinetDewPointKey.set(-5.0f))
      .add(Keys.StatusEvent.placeholderCurrentTemperatureKey.set(20.0f))

  private def publishStatus(): Unit =
    val _ = eventService.defaultPublisher.publish(buildStatusEvent())

  private def publishCommandFailure(message: String): Unit =
    val evt = SystemEvent(assemblyPrefix, Keys.ApsCommandFailureEventEvent.eventKey.eventName)
      .add(Keys.ApsCommandFailureEventEvent.sourceAssemblyKey.set(assemblyPrefix.toString))
      .add(Keys.ApsCommandFailureEventEvent.messageKey.set(message))
      .add(Keys.ApsCommandFailureEventEvent.recoveryStateKey.set(Choice("NOT_USED")))
      .add(Keys.ApsCommandFailureEventEvent.recoveryRetryIndexKey.set(0))
      .add(Keys.ApsCommandFailureEventEvent.recoveryRetryCountKey.set(0))
      .add(Keys.ApsCommandFailureEventEvent.recoveryRetryStateKey.set(Choice("FAILURE")))
    val _ = eventService.defaultPublisher.publish(evt)

  // ---- Command validation + dispatch -------------------------------------

  override def validateCommand(runId: Id, cmd: ControlCommand): ValidateCommandResponse =
    cmd match
      case s: Setup =>
        s.commandName.name match
          case "commandPurgeAir" =>
            if commandState == "PROCESSING" then
              Invalid(runId, CommandIssue.WrongInternalStateIssue(s"$assemblyPrefix is PROCESSING; command rejected"))
            else if s.exists(Keys.CommandPurgeAirCommand.actionKey) then Accepted(runId)
            else Invalid(runId, CommandIssue.MissingKeyIssue("commandPurgeAir requires action (ON/OFF)"))
          case "commandCoolantControlValve" =>
            if commandState == "PROCESSING" then
              Invalid(runId, CommandIssue.WrongInternalStateIssue(s"$assemblyPrefix is PROCESSING; command rejected"))
            else if s.exists(Keys.CommandCoolantControlValveCommand.actionKey) then Accepted(runId)
            else Invalid(runId, CommandIssue.MissingKeyIssue("commandCoolantControlValve requires action (ON/OFF)"))
          case other =>
            Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"$assemblyPrefix does not accept '$other'"))
      case _ =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))

  override def onSubmit(runId: Id, cmd: ControlCommand): SubmitResponse =
    cmd match
      case s: Setup =>
        commandState = "PROCESSING"
        publishStatus()
        val (label, effect) = s.commandName.name match
          case "commandPurgeAir" =>
            val on = s(Keys.CommandPurgeAirCommand.actionKey).head.name == "ON"
            (s"commandPurgeAir ${if on then "ON" else "OFF"}", () => purgeAirOn = on)
          case _ => // commandCoolantControlValve; validateCommand admits nothing else
            val on = s(Keys.CommandCoolantControlValveCommand.actionKey).head.name == "ON"
            (s"commandCoolantControlValve ${if on then "ON" else "OFF"}", () => coolantOn = on)
        log.info(s"$assemblyPrefix: $label (mock valve ${ValveActuationTime.toMillis} ms)")
        after(ValveActuationTime, ctx.system.classicSystem.scheduler)(Future.successful(())).onComplete { _ =>
          effect()
          commandState = "IDLE"
          commandResponseManager.updateCommand(Completed(runId))
          publishStatus()
          log.info(s"$assemblyPrefix: $label complete")
        }
        Started(runId)
      case _ =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))
