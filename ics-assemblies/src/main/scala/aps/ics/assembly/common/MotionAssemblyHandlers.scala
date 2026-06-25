package aps.ics.assembly.common

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import csw.alarm.models.AlarmSeverity
import csw.alarm.models.Key.AlarmKey
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.{LocationRemoved, LocationUpdated, PekkoLocation, TrackingEvent}
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, CommandName, ControlCommand, Setup}
import csw.params.core.generics.Parameter
import csw.params.core.models.{Choice, Id}
import csw.params.core.states.{CurrentState, StateName}
import csw.prefix.models.Prefix
import csw.time.core.models.UTCTime

import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion` as Hcd
import csw.proto.galil.config.ConfigServiceLoader

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Common base for ALL APS-ICS motion assemblies — linear stages and rotating
 * wheels alike. Implements the shared CSW wiring and the SDD §6.1 behaviour:
 *   - load config, track the Galil HCD(s), subscribe to HCD CurrentState
 *   - configure axes (configAxis + configLinearAxis/configRotatingAxis)
 *   - common commands: configure, home, moveToDefaultPosition, stop, abortErrorRecovery
 *   - operational state machine (PreHomed -> Operational -> Degraded/Faulted)
 *   - command state machine (Idle -> Processing -> ErrorRecovery -> Failed)
 *
 * Mechanism-family specialisations sit below this:
 *   - StageAssemblyHandlers  — linear axes; mm<->counts moves
 *   - WheelAssemblyHandlers  — rotating wheels; slot selects + degree/count moves
 * The base is unit-agnostic: it scales configAxis values via the `countsPerUnit`
 * hook (mm for stages, degrees for wheels) and defers `runMoveToDefault` to the
 * family (a linear position vs a wheel slot). Concrete assemblies provide the
 * config resource, the list of axis config keys, the assembly-specific commands,
 * and telemetry publication (which uses the assembly's own generated event keys).
 *
 * Concurrency: ComponentHandlers run on the single TLA thread. The HCD
 * CurrentState subscription delivers on a different thread, so the latest-state
 * fields are @volatile and only ever hold immutable snapshots (single writer =
 * subscription thread; readers = TLA thread). This is the lightweight CSW idiom
 * for read-mostly telemetry; command handling never mutates these.
 *
 * NOTE (first cut): automatic error-recovery (SDD §6.1.3.3) is intentionally
 * simplified — an HCD error transitions commandState to Failed and publishes an
 * apsCommandFailureEvent rather than running the retry/home ladder.
 * abortErrorRecovery is a no-op stub. The full recovery machine is a follow-on
 * once the happy path is proven on hardware.
 */
abstract class MotionAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx):

  import cswCtx._

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  implicit val timeout: Timeout             = Timeout(10.seconds) // default for short interactions
  protected val log                         = loggerFactory.getLogger
  protected val assemblyPrefix: Prefix      = componentInfo.prefix

  // Per-command HCD-wait backstops. The HCD owns the motion and sizes its own
  // watchdog per command (homeAxis ~3 min; positionAxis/offsetAxis scale with
  // distance; stopAxis ~5 s; config commands are immediate). Our wait on the
  // HCD must be >= the HCD's watchdog so the HCD's response (Completed, or its
  // own timeout Error) is what we observe — a shorter wait would spuriously
  // time out long moves/homes the HCD is completing correctly.
  protected val configHcdTimeout: Timeout = Timeout(10.seconds)
  protected val stopHcdTimeout: Timeout   = Timeout(10.seconds)   // >= HCD stopAxis watchdog (~5 s)
  protected val homeHcdTimeout: Timeout   = Timeout(4.minutes)    // >= HCD homeAxis watchdog (~3 min)

  // ---- Subclass contract -------------------------------------------------

  /** Local config resource name (prototype). Production: CSW Configuration Service. */
  protected def configResource: String

  /** Component config, loaded once in [[initialize]] (Config Service or fallback);
   *  subclasses read their own keys from this rather than re-loading. */
  @volatile protected var componentConfig: Config = ConfigFactory.empty()

  /** HOCON object keys for each axis this assembly controls (SDD axis names). */
  protected def axisConfigKeys: List[String]

  /** Publish the assembly's telemetry events (status, axisStatus, ...). Called
   *  after each HCD CurrentState update and on state transitions. */
  protected def publishTelemetry(): Unit

  /** Validate an assembly-specific command (selectSource, positionStage, ...). */
  protected def validateSpecificCommand(runId: Id, setup: Setup): ValidateCommandResponse

  /** Build the dispatch for an assembly-specific command (selectSource,
   *  positionStage, ...). Returns a thunk that performs the HCD submit and
   *  yields the terminal SubmitResponse. The thunk is built once and reused by
   *  the base for both the initial dispatch and any recovery resend, so it must
   *  resolve the demand to an absolute target at build time (e.g. a RELATIVE
   *  move -> current + delta) — never re-derive it from live state — so a resend
   *  reaches the same demanded position. The base manages commandState and CRM. */
  protected def handleSpecificCommand(runId: Id, setup: Setup): () => Future[SubmitResponse]

  /** Counts per the axis's NATIVE unit (mm for stages, degrees for wheels), used
   *  to scale configAxis values to controller counts in [[runConfigure]]. The
   *  native unit is a property of the mechanism family, so the subclass supplies
   *  it; the base never assumes mm. */
  protected def countsPerUnit(a: AxisConfig): Double

  /** moveToDefaultPosition: drive each axis to its configured default. Linear and
   *  wheel assemblies resolve "default" differently (an absolute mm position vs a
   *  wheel slot), so the family provides it. */
  protected def runMoveToDefault(): Future[SubmitResponse]

  // ---- Mutable assembly state (TLA thread) -------------------------------

  protected var axes: List[AxisConfig]          = Nil
  // @volatile: read by publishTelemetry from non-TLA threads (the configure Future
  // callback, and the K-Mirror Tracking Control Actor), written on the TLA thread.
  @volatile protected var operationalState: OperationalState = OperationalState.Faulted // until configured
  @volatile protected var commandState: CommandState         = CommandState.Idle
  // isOnline is inherited from ComponentHandlers (public var); we set it in
  // onGoOnline/onGoOffline and at the end of initialize().

  // ---- HCD connection + latest-state snapshots ---------------------------

  // distinct HCD prefixes -> resolved CommandService (populated by tracking)
  @volatile private var hcdServices: Map[String, CommandService] = Map.empty
  // HCD prefix -> latest lifecycle snapshot
  @volatile protected var latestLifecycle: Map[String, HcdLifecycle] = Map.empty
  // axis name -> latest axis snapshot
  @volatile protected var latestAxis: Map[String, AxisSnapshot] = Map.empty

  private var configuredOnce: Boolean = false
  // initial configure completed: gate for operational-state reconciliation
  @volatile private var configured: Boolean = false
  // set by abortErrorRecovery; checked by an in-flight recovery attempt
  private val abortRequested = new AtomicBoolean(false)
  // alarm raised while the HCD this assembly depends on is Faulted
  private val hcdFaultedAlarm = AlarmKey(assemblyPrefix, "hcdFaulted")
  // throttle telemetry to the SDD rate (1 Hz online, 30 s offline)
  private var lastPublishMs: Long = 0L

  // =========================================================================
  // Lifecycle
  // =========================================================================

  override def initialize(): Unit =
    log.info(s"$assemblyPrefix: initialize")
    // Config Service active version (path namespaced under aps/), else the bundled
    // resource. Loaded once here and shared with subclasses via componentConfig.
    // Config Service path mirrors the component prefix (by-component convention):
    //   APS.ICS.STIM.InsertionStage  ->  APS/ICS/STIM/InsertionStage.conf
    val csPath = componentInfo.prefix.toString.replace('.', '/') + ".conf"
    val loaded = ConfigServiceLoader.load(csPath, configResource, locationService, ctx.system)
    componentConfig = loaded.config
    log.info(s"$assemblyPrefix: config from ${loaded.source}")
    axes = axisConfigKeys.map(k => AxisConfig.fromConfig(k, componentConfig))
    log.info(s"$assemblyPrefix: loaded ${axes.size} axis config(s): " +
      axes.map(a => s"${a.name}->${a.galilHcd}:${a.galilChannel}").mkString(", "))
    isOnline = true
    // HCD connectivity + configure happen when the HCD location arrives.

  override def onShutdown(): Unit =
    log.info(s"$assemblyPrefix: onShutdown")

  override def onGoOffline(): Unit =
    isOnline = false
    log.info(s"$assemblyPrefix: onGoOffline (telemetry -> 30s)")

  override def onGoOnline(): Unit =
    isOnline = true
    log.info(s"$assemblyPrefix: onGoOnline (telemetry -> 1s)")

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}
  override def onOperationsMode(): Unit = {}
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  // =========================================================================
  // HCD location tracking — connections declared in the container ComponentInfo
  // =========================================================================

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    trackingEvent match
      case LocationUpdated(loc: PekkoLocation) =>
        val prefix = loc.connection.componentId.prefix.toString.toLowerCase // match config galilHcd
        log.info(s"$assemblyPrefix: HCD located: ${loc.connection.componentId.prefix}")
        val cs = CommandServiceFactory.make(loc)(ctx.system)
        hcdServices = hcdServices + (prefix -> cs)
        subscribeToHcd(prefix, cs)
        maybeConfigureOnConnect()
      case LocationRemoved(connection) =>
        val prefix = connection.componentId.prefix.toString.toLowerCase
        log.warn(s"$assemblyPrefix: HCD connection removed: ${connection.componentId.prefix}")
        hcdServices = hcdServices - prefix
        transitionOperational(OperationalState.Faulted)
        publishTelemetry()
      case other =>
        log.debug(s"$assemblyPrefix: tracking event: $other")

  /** True once every distinct HCD this assembly's axes use is resolved. */
  protected def allHcdsConnected: Boolean =
    axes.map(_.galilHcd).distinct.forall(hcdServices.contains)

  protected def hcdFor(axis: AxisConfig): Option[CommandService] = hcdServices.get(axis.galilHcd)

  private def maybeConfigureOnConnect(): Unit =
    if allHcdsConnected && !configuredOnce then
      configuredOnce = true
      log.info(s"$assemblyPrefix: all HCDs connected; running startup configure")
      runConfigure().onComplete { res =>
        if res.toOption.exists(_.isInstanceOf[Completed]) then
          configured = true
          reconcileOperationalState() // derive PreHomed/Operational/Faulted from HCD
        else
          log.error(s"$assemblyPrefix: startup configure failed: $res")
          transitionOperational(OperationalState.Faulted)
        publishTelemetry()
      }

  // =========================================================================
  // HCD CurrentState subscription
  // =========================================================================

  private def subscribeToHcd(hcdPrefix: String, cs: CommandService): Unit =
    // lifecycle
    cs.subscribeCurrentState(
      Set(StateName(Hcd.CurrentStateCurrentState.eventKey.eventName.name)),
      curr => onHcdLifecycle(hcdPrefix, curr)
    )
    // per-axis current state + command state for the channels on this HCD
    val channelsHere = axes.filter(_.galilHcd == hcdPrefix)
    val names = channelsHere.flatMap { a =>
      Set(StateName(s"CurrentStateAxis${a.galilChannel}"), StateName(s"CommandStateAxis${a.galilChannel}"))
    }.toSet
    if names.nonEmpty then
      cs.subscribeCurrentState(names, curr => onHcdAxisState(curr))
    // mechanism-family extra subscriptions (e.g. pupil-mask wheels: InputOutputState)
    subscribeExtra(hcdPrefix, cs)

  /** Hook for mechanism-family-specific HCD CurrentState subscriptions beyond the
   *  common lifecycle + per-axis state. Called once per HCD as it connects, after
   *  the lifecycle and per-axis subscriptions are established. A family that needs
   *  extra HCD telemetry (e.g. pupil-mask wheels subscribe to InputOutputState for
   *  the detent sensor bits) overrides this to add its own subscriptions. The
   *  override should itself filter to the HCD(s) it cares about. Default: none. */
  protected def subscribeExtra(hcdPrefix: String, cs: CommandService): Unit = ()

  private def onHcdLifecycle(hcdPrefix: String, curr: CurrentState): Unit =
    if curr.exists(Hcd.CurrentStateCurrentState.stateKey) then
      val state = curr(Hcd.CurrentStateCurrentState.stateKey).head.name
      val errMsg = if curr.exists(Hcd.CurrentStateCurrentState.controllerErrorMsgKey)
        then curr(Hcd.CurrentStateCurrentState.controllerErrorMsgKey).head else ""
      latestLifecycle = latestLifecycle + (hcdPrefix -> HcdLifecycle(state, errMsg))
      // Operational state (incl. HCD-fault -> Faulted + alarm) is derived in one
      // place from the latest HCD signals.
      reconcileOperationalState()
      throttledPublish()

  private def onHcdAxisState(curr: CurrentState): Unit =
    // The CurrentStateAxis<x> param keys are axis-independent (same names across
    // A-H), so we read with the AxisA key objects regardless of channel and key
    // the snapshot by matching the StateName suffix to our configured channel.
    val sn = curr.stateName.name // e.g. "CurrentStateAxisB" or "CommandStateAxisB"
    val channel = sn.takeRight(1)
    axes.find(_.galilChannel == channel).foreach { a =>
      if sn.startsWith("CurrentStateAxis") then
        val k = Hcd.CurrentStateAxisACurrentState
        val prev = latestAxis.getOrElse(a.name, AxisSnapshot.Unknown)
        val snap = prev.copy(
          positionCounts = if curr.exists(k.positionKey) then curr(k.positionKey).head.toDouble else prev.positionCounts,
          velocityCounts = if curr.exists(k.velocityKey) then curr(k.velocityKey).head.toDouble else prev.velocityCounts,
          hcdAxisState   = if curr.exists(k.axisStateKey) then curr(k.axisStateKey).head.name else prev.hcdAxisState,
          inPosition     = if curr.exists(k.inPositionKey) then curr(k.inPositionKey).head else prev.inPosition,
          homed          = if curr.exists(k.homedKey) then curr(k.homedKey).head else prev.homed,
          axisErrorMsg   = if curr.exists(k.axisErrorMsgKey) then curr(k.axisErrorMsgKey).head else prev.axisErrorMsg
        )
        latestAxis = latestAxis + (a.name -> enrichAxisSnapshot(snap, curr))
        reconcileOperationalState()
        afterAxisUpdate(a, latestAxis(a.name))
      // CommandStateAxis<x> currently unused in the first cut; subscribed for
      // future moving/activeThread tracking.
    }
    throttledPublish()

  /** Hook: called after each axis snapshot update with the fresh snapshot. Default
   *  no-op; the K-Mirror overrides it to feed live position to its Tracking Control
   *  Actor for tracking convergence detection. */
  protected def afterAxisUpdate(a: AxisConfig, snap: AxisSnapshot): Unit = ()

  /** Hook for mechanism-family-specific CurrentStateAxis fields. The base folds
   *  the common axis keys (position/velocity/state/inPosition/homed/error) into
   *  `snap`; a family that publishes extra axis telemetry (e.g. wheels: achieved
   *  slot and angle) overrides this to read those keys from `curr` and copy them
   *  in. Default: identity (linear stages have nothing extra). */
  protected def enrichAxisSnapshot(snap: AxisSnapshot, curr: CurrentState): AxisSnapshot = snap

  /** Project the operational state from the latest HCD signals (trust the HCD,
   *  in both directions). Runs once the initial configure has completed.
   *   - HCD controller Faulted  -> assembly Faulted, raise hcdFaulted alarm.
   *     No assembly-initiated recovery (a faulted HCD may serve many assemblies);
   *     we mirror it out of Faulted when the controller returns to Ready.
   *   - else axis homed          -> Operational
   *   - else                      -> PreHomed
   *  Axis `error` is handled per-command by the recovery routine, not here. */
  private def reconcileOperationalState(): Unit =
    if configured then
      if hcdStateChoice == "FAULTED" then
        if operationalState != OperationalState.Faulted then raiseHcdFaultedAlarm()
        transitionOperational(OperationalState.Faulted)
      else
        if operationalState == OperationalState.Faulted then clearHcdFaultedAlarm()
        val allHomed = axes.forall(a => latestAxis.get(a.name).exists(_.homed))
        transitionOperational(if allHomed then OperationalState.Operational else OperationalState.PreHomed)

  private def raiseHcdFaultedAlarm(): Unit = setAlarm(AlarmSeverity.Major)
  private def clearHcdFaultedAlarm(): Unit = setAlarm(AlarmSeverity.Okay)

  /** Set the hcdFaulted alarm severity, tolerating an absent Alarm Service (the
   *  alarm only surfaces with `csw-services -a` and the alarm loaded; otherwise
   *  the failed future is logged and the assembly continues). */
  private def setAlarm(severity: AlarmSeverity): Unit =
    alarmService.setSeverity(hcdFaultedAlarm, severity).onComplete {
      case Success(_) => log.info(s"$assemblyPrefix: hcdFaulted alarm -> $severity")
      case Failure(t) => log.warn(s"$assemblyPrefix: could not set hcdFaulted alarm to $severity " +
        s"(Alarm Service unavailable or alarm undefined?): ${t.getMessage}")
    }

  private def throttledPublish(): Unit =
    val now = System.currentTimeMillis()
    val interval = if isOnline then 1000L else 30000L
    if now - lastPublishMs >= interval then
      lastPublishMs = now
      publishTelemetry()

  // =========================================================================
  // Command validation + dispatch
  // =========================================================================

  override def validateCommand(runId: Id, cmd: ControlCommand): ValidateCommandResponse =
    val name = cmd.commandName.name
    // Operational-state gate (SDD §6.1.3.2)
    operationalState match
      case OperationalState.Faulted =>
        return Invalid(runId, CommandIssue.WrongInternalStateIssue(s"$assemblyPrefix is Faulted"))
      case OperationalState.PreHomed if !Set("configure", "home").contains(name) =>
        return Invalid(runId, CommandIssue.WrongInternalStateIssue(
          s"$assemblyPrefix is Pre-Homed; only configure and home are accepted"))
      case _ => // Operational / Degraded accept all
    // Command-state gate (SDD §6.1.3.3)
    commandState match
      case CommandState.Processing =>
        return Invalid(runId, CommandIssue.WrongInternalStateIssue(s"$assemblyPrefix busy (Processing)"))
      case CommandState.ErrorRecovery if name != "abortErrorRecovery" =>
        return Invalid(runId, CommandIssue.WrongInternalStateIssue(
          s"$assemblyPrefix in Error Recovery; only abortErrorRecovery accepted"))
      case _ =>
    // Per-command validation
    cmd match
      case s: Setup => validateSetup(runId, s)
      case _        => Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))

  private val commonCommands = Set("configure", "home", "moveToDefaultPosition", "stop", "abortErrorRecovery")

  private def validateSetup(runId: Id, s: Setup): ValidateCommandResponse =
    if commonCommands.contains(s.commandName.name) then Accepted(runId)
    else validateSpecificCommand(runId, s)

  override def onSubmit(runId: Id, cmd: ControlCommand): SubmitResponse =
    cmd match
      // abortErrorRecovery is handled out-of-band so it doesn't disturb the
      // in-flight command's commandState: it signals the running recovery to
      // stop and best-effort-halts the axis. The original command then resolves
      // as Cancelled (see onComplete below).
      case s: Setup if s.commandName.name == "abortErrorRecovery" =>
        log.warn(s"$assemblyPrefix: abortErrorRecovery — halting recovery and stopping the axis")
        abortRequested.set(true)
        val _ = runStop() // fire-and-forget halt
        Completed(runId)

      case s: Setup =>
        commandState = CommandState.Processing
        publishTelemetry()
        val name = s.commandName.name
        // Build the dispatch thunk ONCE so a recovery resend repeats the exact
        // same demand. Common commands are idempotent on resend (home re-homes,
        // absolute moves re-target); specific commands resolve their demand at
        // build time (see handleSpecificCommand).
        val dispatch: () => Future[SubmitResponse] = name match
          case "configure"             => () => runConfigure()
          case "home"                  => () => runHome()
          case "moveToDefaultPosition" => () => runMoveToDefault()
          case "stop"                  => () => runStop()
          case _                       => handleSpecificCommand(runId, s)
        // Motion commands get one axis-error recovery attempt; configure/stop do not.
        val result: Future[SubmitResponse] =
          if Set("configure", "stop").contains(name) then dispatch()
          else withRecovery(runId, dispatch)
        result.onComplete { tryResp =>
          val resp = tryResp.getOrElse(Error(runId, s"internal error: ${tryResp.failed.get.getMessage}"))
          resp match
            case _: Completed =>
              if name == "home" then transitionOperational(OperationalState.Operational)
              commandState = CommandState.Idle
            case _: Cancelled =>
              // aborted recovery: command did not complete, but the assembly is
              // ready again (not a latched failure)
              commandState = CommandState.Idle
              log.warn(s"$assemblyPrefix: command $name aborted during error recovery")
            case _ =>
              commandState = CommandState.Failed
              log.error(s"$assemblyPrefix: command $name failed: $resp")
          // Re-stamp with the assembly's runId (the inner HCD responses carry their
          // own ids). The repo builds responses with the right id inline rather than
          // using withRunId, so we do the same here.
          val stamped: SubmitResponse = resp match
            case _: Completed  => Completed(runId)
            case e: Error      => Error(runId, e.message)
            case i: Invalid    => Invalid(runId, i.issue)
            case _: Cancelled  => Cancelled(runId)
            case _: Locked     => Locked(runId)
            case other         => Error(runId, s"unexpected response: $other")
          commandResponseManager.updateCommand(stamped)
          publishTelemetry()
        }
        Started(runId)
      case _ =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))

  // ---- axis-error recovery ------------------------------------------------

  /** Wrap a motion command so an axis-error failure triggers one recovery
   *  attempt. Non-axis-error failures (e.g. a soft-limit Invalid) pass through. */
  private def withRecovery(runId: Id, dispatch: () => Future[SubmitResponse]): Future[SubmitResponse] =
    dispatch().flatMap { resp =>
      if isRecoverableFailure(resp) then
        abortRequested.set(false)
        commandState = CommandState.ErrorRecovery
        publishTelemetry()
        log.warn(s"$assemblyPrefix: command did not reach demand ($resp); entering error recovery")
        recover(runId, dispatch)
      else Future.successful(resp)
    }

  /** A command that was accepted but failed to reach its demand is recoverable:
   *  stop and resend once. Gated on `Error` specifically:
   *   - `Invalid` (soft-limit, wrong-state, missing key) is a deterministic
   *     rejection caught at validateCommand — it never reaches here and would
   *     only fail again on resend.
   *   - `Cancelled` is our own abort — it must not be retried.
   *   - `Locked` means another client owns the component — not ours to retry.
   *  An axis going to HCD `error` state is one cause of an `Error`; an
   *  interrupted or underran move is another — both are recovered. The choice
   *  of recovery action (e.g. stop-vs-home by cause) lives in `recover`, which
   *  can inspect the axis snapshot to decide. */
  private def isRecoverableFailure(resp: SubmitResponse): Boolean =
    resp.isInstanceOf[Error]

  /** Recovery strategy. Override to add per-error-type intelligence (e.g. choose
   *  home vs stop by error type). Default: stop the axis, then resend the command
   *  once. Stop's completion is the "axis back to idle" gate (the HCD's stop
   *  watcher confirms moving=false before completing). A second failure -> the
   *  command is reported Failed; an abort in between -> Cancelled. */
  protected def recover(runId: Id, dispatch: () => Future[SubmitResponse]): Future[SubmitResponse] =
    runStop().flatMap { stopResp =>
      if abortRequested.get() then
        Future.successful(Cancelled(runId))
      else if !stopResp.isInstanceOf[Completed] then
        Future.successful(Error(runId, s"error recovery: stop failed ($stopResp)"))
      else
        dispatch().map { retry =>
          if abortRequested.get() then
            // abort fired during the resend: its stop interrupted the move, so
            // the retry comes back as an Error — report Cancelled (ready again),
            // not a latched failure.
            Cancelled(runId)
          else
            retry match
              case c: Completed => c
              case other        => Error(runId, s"command failed after recovery retry ($other)")
        }
    }

  // =========================================================================
  // Common command implementations (mapped to HCD commands)
  // =========================================================================

  /** Build a Setup addressed to the HCD for one axis command. */
  protected def hcdSetup(cmd: CommandName, params: Parameter[?]*): Setup =
    Setup(assemblyPrefix, cmd, None).madd(params*)

  /** Submit a Setup to the HCD that owns `axis`, waiting up to `hcdTimeout`
   *  for the final response; Error if not connected. */
  protected def submitToHcd(axis: AxisConfig, setup: Setup, hcdTimeout: Timeout): Future[SubmitResponse] =
    hcdFor(axis) match
      case Some(cs) => cs.submitAndWait(setup)(hcdTimeout)
      case None     => Future.successful(Error(Id(), s"HCD ${axis.galilHcd} not connected"))

  /** Run a sequence of per-axis HCD submits, completing only if all succeed. */
  protected def submitAllAxes(perAxis: AxisConfig => Future[SubmitResponse]): Future[SubmitResponse] =
    val runId = Id()
    Future.sequence(axes.map(perAxis)).map { responses =>
      responses.find(r => !r.isInstanceOf[Completed]) match
        case Some(bad) => bad
        case None      => Completed(runId)
    }

  /** configure: configAxis + configLinearAxis/configRotatingAxis per axis. Config
   *  values are in the axis's native unit (mm for stages, degrees for wheels);
   *  `countsPerUnit` scales them to controller counts here. */
  protected def runConfigure(): Future[SubmitResponse] =
    submitAllAxes { a =>
      val cpu = countsPerUnit(a)
      val cfg = hcdSetup(
        Hcd.ConfigAxisCommand.commandName,
        Hcd.ConfigAxisCommand.axisKey.set(Choice(a.galilChannel)),
        Hcd.ConfigAxisCommand.velocityKey.set((a.velocity * cpu).toFloat),
        Hcd.ConfigAxisCommand.accelerationKey.set((a.acceleration * cpu).toFloat),
        Hcd.ConfigAxisCommand.decelerationKey.set((a.deceleration * cpu).toFloat),
        Hcd.ConfigAxisCommand.indexOffsetKey.set(Math.round(a.indexOffsetMm * cpu).toFloat),
        Hcd.ConfigAxisCommand.indexSpeedKey.set((a.indexSpeed * cpu).toFloat),
        Hcd.ConfigAxisCommand.inPositionThresholdKey.set(Math.round(a.inPositionThresholdMm * cpu).toFloat)
      )
      val shape =
        if a.isRotational then
          hcdSetup(
            Hcd.ConfigRotatingAxisCommand.commandName,
            Hcd.ConfigRotatingAxisCommand.axisKey.set(Choice(a.galilChannel)),
            Hcd.ConfigRotatingAxisCommand.algorithmKey.set(Choice(a.rotationalMethod.getOrElse("shortest")))
          )
        else
          hcdSetup(
            Hcd.ConfigLinearAxisCommand.commandName,
            Hcd.ConfigLinearAxisCommand.axisKey.set(Choice(a.galilChannel)),
            Hcd.ConfigLinearAxisCommand.lowerLimitKey.set(Math.round(a.lowerLimitMm * cpu).toFloat),
            Hcd.ConfigLinearAxisCommand.upperLimitKey.set(Math.round(a.upperLimitMm * cpu).toFloat)
          )
      submitToHcd(a, cfg, configHcdTimeout).flatMap {
        case _: Completed => submitToHcd(a, shape, configHcdTimeout)
        case bad          => Future.successful(bad)
      }
    }

  protected def runHome(): Future[SubmitResponse] =
    submitAllAxes { a =>
      submitToHcd(a, hcdSetup(Hcd.HomeAxisCommand.commandName, Hcd.HomeAxisCommand.axisKey.set(Choice(a.galilChannel))), homeHcdTimeout)
    }

  protected def runStop(): Future[SubmitResponse] =
    submitAllAxes { a =>
      submitToHcd(a, hcdSetup(Hcd.StopAxisCommand.commandName, Hcd.StopAxisCommand.axisKey.set(Choice(a.galilChannel))), stopHcdTimeout)
    }

  // =========================================================================
  // State helpers
  // =========================================================================

  protected def transitionOperational(s: OperationalState): Unit =
    if operationalState != s then
      log.info(s"$assemblyPrefix: operationalState ${operationalState} -> $s")
      operationalState = s

  /** Worst-case HCD lifecycle choice across all HCDs this assembly uses. */
  protected def hcdStateChoice: String =
    val states = axes.map(_.galilHcd).distinct.map(p => latestLifecycle.getOrElse(p, HcdLifecycle.Unknown).choice)
    if states.contains("FAULTED") then "FAULTED"
    else if states.nonEmpty && states.forall(_ == "READY") then "READY"
    else "UNINITIALIZED"