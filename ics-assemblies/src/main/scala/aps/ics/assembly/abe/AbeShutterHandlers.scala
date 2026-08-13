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

import aps.ics.assembly.icd.AbeShutterKeys.`ICS.ABE.Shutter` as Keys

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

/**
 * APS-ICS ABE Shutter assembly MOCK (self-contained), ICD section 21.
 *
 * The ICD's send-model declares `setBit`/`faultReset` to ICS.HCD.GalilMotion —
 * the real assembly actuates the shutter through the Galil digital outputs and
 * reads blade state back from the HCD's InputOutputState CurrentState. The
 * output-bit assignments are NOT in the model, so this mock is deliberately
 * self-contained: no HCD connection (`connections = []` in the container conf),
 * blades change state after a fixed actuation delay. Graduate to
 * setBit-through-the-HCD when the digital output map is defined; the HCD side
 * (`setBit`) already exists.
 *
 * Mock semantics:
 *   - `commandShutter` (OPEN/CLOSE): Started, then both blades take the
 *     commanded state after [[AbeShutterHandlers.ShutterTravelTime]]; Completed
 *     via CRM. One command at a time (PROCESSING gates validation), matching
 *     the detector mocks' busy gating.
 *   - `status` @1 Hz per the publish model. `hcdState` is READY by fiat: no
 *     HCD is bound in the mock. `shutterErrorIndicator` is always NO.
 *   - `apsCommandFailureEvent` on an Error response (unreachable in normal
 *     mock operation, wired for parity with the detector mocks).
 *   - startupMetrics: declared in the model, not published — consistent with
 *     the assemblies' known declared-but-never-published batch (PROJECT_STATE
 *     section 10); publish it there when that batch lands.
 */
class AbeShutterHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx):

  import cswCtx._

  private val log                                   = loggerFactory.getLogger
  private implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val assemblyPrefix                        = componentInfo.prefix

  /** Mock actuation time for the blade pair (both blades move together). */
  private val ShutterTravelTime: FiniteDuration = 1500.millis

  @volatile private var bladeA: String       = "CLOSED"
  @volatile private var bladeB: String       = "CLOSED"
  @volatile private var commandState: String = "IDLE"

  private var statusTimer: Option[Cancellable] = None

  // ---- Lifecycle ---------------------------------------------------------

  override def initialize(): Unit =
    // Default this component's log level to INFO at runtime; see the rationale
    // comment in MotionAssemblyHandlers.initialize() (and the underlying CSW
    // limitation analysis in GalilHcd.scala, "KNOWN CSW LIMITATION").
    LogAdminUtil.setComponentLogLevel(componentInfo.prefix, Level.INFO)
    log.info(s"$assemblyPrefix: initialize (ABE Shutter mock, self-contained; blades start CLOSED)")
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
    SystemEvent(assemblyPrefix, Keys.StatusEvent.eventKey.eventName)
      .add(Keys.StatusEvent.assemblyStateKey.set(Choice("READY")))
      .add(Keys.StatusEvent.hcdStateKey.set(Choice("READY"))) // by fiat: no HCD bound in the mock
      .add(Keys.StatusEvent.commandStateKey.set(Choice(commandState)))
      .add(Keys.StatusEvent.shutterBladeAStateKey.set(Choice(bladeA)))
      .add(Keys.StatusEvent.shutterBladeBStateKey.set(Choice(bladeB)))
      .add(Keys.StatusEvent.shutterErrorIndicatorKey.set(Choice("NO")))

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
          case "commandShutter" =>
            if commandState == "PROCESSING" then
              Invalid(runId, CommandIssue.WrongInternalStateIssue(s"$assemblyPrefix is PROCESSING; command rejected"))
            else if s.exists(Keys.CommandShutterCommand.commandKey) then Accepted(runId)
            else Invalid(runId, CommandIssue.MissingKeyIssue("commandShutter requires command (OPEN/CLOSE)"))
          case other =>
            Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"$assemblyPrefix does not accept '$other'"))
      case _ =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))

  override def onSubmit(runId: Id, cmd: ControlCommand): SubmitResponse =
    cmd match
      case s: Setup =>
        val target = s(Keys.CommandShutterCommand.commandKey).head.name // OPEN or CLOSE
        commandState = "PROCESSING"
        publishStatus()
        log.info(s"$assemblyPrefix: commandShutter $target (mock travel ${ShutterTravelTime.toMillis} ms)")
        after(ShutterTravelTime, ctx.system.classicSystem.scheduler)(Future.successful(())).onComplete { _ =>
          val bladeState = if target == "OPEN" then "OPEN" else "CLOSED"
          bladeA = bladeState
          bladeB = bladeState
          commandState = "IDLE"
          commandResponseManager.updateCommand(Completed(runId))
          publishStatus()
          log.info(s"$assemblyPrefix: commandShutter $target complete (blades $bladeState)")
        }
        Started(runId)
      case _ =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))
