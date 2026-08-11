package aps.ics.assembly.common

import org.apache.pekko.actor.Cancellable
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
import csw.logging.client.commons.LogAdminUtil
import csw.logging.models.Level
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, CommandName, ControlCommand, Setup}
import csw.params.core.generics.Parameter
import csw.params.core.models.{Choice, Id}
import csw.params.core.states.{CurrentState, StateName}
import csw.prefix.models.{Prefix, Subsystem}
import csw.time.core.models.UTCTime

import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion` as Hcd
import csw.proto.galil.config.ConfigServiceLoader
import csw.proto.galil.hcd.CpuLoadMonitor

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
  // set by stop; checked by an in-flight command so a deliberate interruption
  // terminates as Cancelled instead of being treated as a recoverable axis error.
  // Distinct from abortRequested: stop halts motion in progress (Processing),
  // whereas abortErrorRecovery only bails an active recovery (ErrorRecovery).
  private val stopRequested = new AtomicBoolean(false)
  // alarm raised while the HCD this assembly depends on is Faulted
  private val hcdFaultedAlarm = AlarmKey(assemblyPrefix, "hcdFaulted")
  // CSW alarm severities EXPIRE (csw-alarm reference.conf: refresh-interval 3s ×
  // max-missed-refresh-counts 3 ⇒ ~9s TTL) and lapse to Disconnected unless
  // refreshed — so the truthful value, including Okay, must be re-set
  // continuously. Disconnected then correctly means "assembly not reporting".
  // The severity is DERIVED from the live operationalState at every push
  // (Faulted ⇒ Major, else Okay) — never cached: assemblies are BORN Faulted
  // ("until configured"), so a shadow variable initialized to Okay would have
  // the refresh assert a healthy alarm on a Faulted-from-birth assembly (S82).
  private def hcdFaultedSeverity: AlarmSeverity =
    if operationalState == OperationalState.Faulted then AlarmSeverity.Major else AlarmSeverity.Okay
  private var alarmRefreshTimer: Option[Cancellable] = None
  private val AlarmRefreshInterval = 5.seconds // must stay under the ~9s TTL
  // edge-logging state for the quiet refresh path: warn once when refreshes
  // start failing, info once when they recover — never per-tick
  private val alarmRefreshFailing = new AtomicBoolean(false)
  // throttle telemetry to the SDD rate (1 Hz online, 30 s offline)
  private var lastPublishMs: Long = 0L

  // =========================================================================
  // Lifecycle
  // =========================================================================

  override def initialize(): Unit =
    // Default this component's log level to INFO at runtime. The static
    // component-log-levels HOCON block cannot reliably target deeply-nested
    // prefixes like "APS.ICS.STIM.InsertionStage" (Config.entrySet() key
    // rendering does not round-trip back to the runtime Prefix — see the full
    // analysis in GalilHcd.scala, "KNOWN CSW LIMITATION"). This runtime path
    // writes the live Prefix into the same map LoggerImpl reads per log call.
    // Primarily silences the CSW framework's per-message DEBUG chatter
    // (SupervisorBehavior/ComponentBehavior "received message"), which the
    // engineering UI's 5s GetSupervisorLifecycleState polling turns into a
    // steady stream for whichever assembly is on screen. Elevate a single
    // assembly back to DEBUG at runtime via LogAdminUtil / the CSW admin API;
    // the application.conf logLevel=debug floor keeps that elevation possible.
    LogAdminUtil.setComponentLogLevel(componentInfo.prefix, Level.INFO)

    // Quiet the CSW alarm CLIENT library's per-call chatter (CSW.alarm_service_lib:
    // DEBUG get-severity/metadata/status + INFO "Updating current severity" on
    // EVERY setSeverity). With the 5s hcdFaulted refresh across 16 assemblies
    // that is a flood. WARN keeps genuine problems visible. JVM-wide effect
    // (LogAdminUtil's map is process-global); idempotent across the assemblies
    // sharing this container. Logger prefix verified against CSW 6.0.0
    // AlarmServiceLogger: Prefix(CSW, "alarm_service_lib").
    LogAdminUtil.setComponentLogLevel(Prefix(Subsystem.CSW, "alarm_service_lib"), Level.WARN)

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

    // Keep the hcdFaulted severity alive under the ~9s alarm TTL, pushing the
    // severity derived from the live operationalState each tick (see
    // hcdFaultedSeverity). Runnable is a SAM; runs on the component's ec.
    alarmRefreshTimer = Some(ctx.system.scheduler.scheduleWithFixedDelay(
      AlarmRefreshInterval, AlarmRefreshInterval)(() => refreshAlarm()))

    // Per-JVM CPU load telemetry (REQ-2-APS-0621). Singleton across the process: the
    // first ICS assembly to initialise starts it, and because all assemblies share one
    // container/JVM this yields exactly ONE cpuLoad event for the whole process, published
    // under the fixed container prefix (CpuLoadMonitor.AssemblyContainerPrefix) rather than
    // any single component's. getProcessCpuLoad covers the entire JVM, so that one event
    // reflects every co-located component's load. No stopOnce here - it is a JVM-lifetime
    // daemon whose scheduler is cancelled when the container's ActorSystem terminates.
    CpuLoadMonitor.startOnce(
      CpuLoadMonitor.AssemblyContainerPrefix, eventService.defaultPublisher, timeServiceScheduler, log, 1.second)

  override def onShutdown(): Unit =
    alarmRefreshTimer.foreach(_.cancel())
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
        // Immediate alarm push on entering Faulted via connection loss —
        // previously only the HCD-controller-Faulted reconcile path raised.
        // (The 5s derived refresh would catch it anyway; this removes latency.)
        if operationalState != OperationalState.Faulted then raiseHcdFaultedAlarm()
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

  /** Immediate push on an operationalState TRANSITION, with an INFO breadcrumb.
   *  The explicit severity argument exists only for the log line; correctness
   *  does not depend on it — the 5s refreshAlarm() re-derives from the live
   *  operationalState, so a missed or mis-ordered push self-heals within one
   *  refresh tick. Tolerates an absent Alarm Service (the alarm only surfaces
   *  with `csw-services -a` and the alarm seeded via scripts/load-alarms.sh). */
  private def setAlarm(severity: AlarmSeverity): Unit =
    alarmService.setSeverity(hcdFaultedAlarm, severity).onComplete {
      case Success(_) => log.info(s"$assemblyPrefix: hcdFaulted alarm -> $severity")
      case Failure(t) => log.warn(s"$assemblyPrefix: could not set hcdFaulted alarm to $severity " +
        s"(Alarm Service unavailable or alarm not seeded? scripts/load-alarms.sh): ${t.getMessage}")
    }

  /** Periodic keep-alive: push the severity DERIVED from the live
   *  operationalState (see hcdFaultedSeverity) under the ~9s TTL so the alarm
   *  reflects truth continuously — including for assemblies that are Faulted
   *  from birth and never made a transition INTO Faulted. Quiet by design —
   *  logs only on the failure/recovery EDGES, never per tick (a 5s log
   *  metronome is exactly what S82's logging rework removed). */
  private def refreshAlarm(): Unit =
    val severity = hcdFaultedSeverity
    alarmService.setSeverity(hcdFaultedAlarm, severity).onComplete {
      case Success(_) =>
        if alarmRefreshFailing.compareAndSet(true, false) then
          log.info(s"$assemblyPrefix: hcdFaulted alarm refresh recovered " +
            s"(current severity $severity)")
      case Failure(t) =>
        if alarmRefreshFailing.compareAndSet(false, true) then
          log.warn(s"$assemblyPrefix: hcdFaulted alarm refresh failing " +
            s"(Alarm Service unavailable or alarm not seeded? scripts/load-alarms.sh): ${t.getMessage}")
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
      case CommandState.Processing if name != "stop" =>
        // Processing isolates normal commands (SDD §6.1.3.3.2), but `stop` is the
        // one command allowed to interrupt an in-flight command — otherwise a stop
        // could never halt motion already in progress. It is handled out-of-band in
        // onSubmit and forces the in-flight command to resolve Cancelled.
        return Invalid(runId, CommandIssue.WrongInternalStateIssue(s"$assemblyPrefix busy (Processing)"))
      case CommandState.ErrorRecovery if name != "abortErrorRecovery" =>
        // During recovery the escape is abortErrorRecovery (which also halts the
        // axis), not stop — keeps the two concerns distinct (SDD §6.1.3.3.3).
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

      // stop is the one command that may interrupt an in-flight command. It is
      // handled out-of-band (like abortErrorRecovery) so it does not take the
      // Processing slot itself: it flags the in-flight command to terminate as
      // Cancelled (see withRecovery), halts every axis, and resolves when the halt
      // completes. commandState is driven by the interrupted command's resolution
      // (or stays Idle if nothing was in flight), not by stop.
      case s: Setup if s.commandName.name == "stop" =>
        log.info(s"$assemblyPrefix: stop — interrupting any in-flight command and halting axes")
        stopRequested.set(true)
        runStop().onComplete { tryResp =>
          val resp = tryResp.getOrElse(Error(runId, s"stop failed: ${tryResp.failed.get.getMessage}"))
          val stamped: SubmitResponse = resp match
            case _: Completed => Completed(runId)
            case e: Error     => Error(runId, e.message)
            case i: Invalid   => Invalid(runId, i.issue)
            case _: Cancelled => Cancelled(runId)
            case _: Locked    => Locked(runId)
            case other        => Error(runId, s"stop failed: $other")
          commandResponseManager.updateCommand(stamped)
          publishTelemetry()
        }
        Started(runId)

      case s: Setup =>
        // A fresh normal command clears any prior stop signal (only one normal
        // command is in flight at a time, and stop is handled above, so this is the
        // safe reset point).
        stopRequested.set(false)
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
      if stopRequested.get() then
        // A stop interrupted this command on purpose: terminate as Cancelled and do
        // NOT treat the interruption Error as a recoverable axis fault (which would
        // re-issue the move and fight the stop).
        Future.successful(Cancelled(runId))
      else if isRecoverableFailure(resp) then
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
      if abortRequested.get() || stopRequested.get() then
        Future.successful(Cancelled(runId))
      else if !stopResp.isInstanceOf[Completed] then
        Future.successful(Error(runId, s"error recovery: stop failed ($stopResp)"))
      else
        dispatch().map { retry =>
          if abortRequested.get() || stopRequested.get() then
            // abort or stop fired during the resend: its stop interrupted the move,
            // so the retry comes back as an Error — report Cancelled (ready again),
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