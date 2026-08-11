package aps.ics.assembly.common

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.Timeout
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, Setup}
import csw.params.core.generics.KeyType._
import csw.params.core.models.{Choice, Id, Units}
import csw.params.core.states.CurrentState

import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion` as Hcd

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Common base for APS-ICS rotating WHEEL assemblies (SDD §7) — filter wheels and
 * pupil-mask wheels. A single ROTATING axis whose user unit is degrees and whose
 * operational positions are discrete numbered slots.
 *
 * Specialises [[MotionAssemblyHandlers]] with:
 *   - countsPerUnit = counts-per-degree (derived from countsPerRevolution)
 *   - the §7 common wheel commands:
 *       positionWheel(1..N)  -> HCD selectWheel(slot)         (EngUI slot select)
 *       positionMotor(...)   -> WHEEL target: HCD positionWheel (degrees)
 *                               MOTOR target: HCD positionAxis  (counts)
 *   - moveToDefaultPosition = select the configured default slot
 *   - reading the achieved slot (wheelPosition) and angle (angularPosition) from
 *     the HCD CurrentStateAxis into the snapshot
 *
 * The mechanism-specific SELECT command (selectFilter for filter wheels,
 * selectPupilMask for pupil-mask wheels) resolves an optic NAME to a slot using
 * the Wheel Position N Assignment config (SDD Table 7-1); that name->slot map and
 * the select command name are supplied by the concrete subclass.
 *
 * Both RELATIVE engineering forms (positionMotor WHEEL/REL and MOTOR/REL) resolve
 * to an absolute target at intake (current angle/counts + delta), so a recovery
 * resend repeats the same demand — consistent with the base's recovery contract.
 *
 * Wheels are single-axis: all helpers operate on axes.headOption.
 */
abstract class WheelAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends MotionAssemblyHandlers(ctx, cswCtx):

  // ---- unit hook: degrees -------------------------------------------------

  /** Native unit is degrees: counts-per-unit is counts-per-degree (from
   *  countsPerRevolution / 360). */
  override protected def countsPerUnit(a: AxisConfig): Double = a.countsPerDegree

  /** Backstop wait for a wheel move: worst-case full revolution x2 + margin,
   *  floored at 10 s. A select also waits for the sensor/detent profile, well
   *  within this. */
  protected def wheelMoveTimeout(a: AxisConfig): Timeout =
    val v = if a.velocity > 0 then a.velocity else 1.0
    Timeout(math.max(10.0, (360.0 / v) * 2.0 + 5.0).seconds)

  // ---- HCD move helpers ---------------------------------------------------

  /** Select a numbered slot: HCD selectWheel(axis, position=slot). The HCD/embedded
   *  runs the full sensor+detent positioning profile and reports the achieved slot
   *  back via CurrentStateAxis.wheelPosition. */
  protected def selectWheelSlot(a: AxisConfig, slot: Int): Future[SubmitResponse] =
    submitToHcd(a, hcdSetup(
      Hcd.SelectWheelCommand.commandName,
      Hcd.SelectWheelCommand.axisKey.set(Choice(a.galilChannel)),
      Hcd.SelectWheelCommand.positionKey.set(slot)
    ), wheelMoveTimeout(a))

  /** Absolute angular move (engineering): HCD positionWheel(axis, position=deg). */
  protected def positionWheelDeg(a: AxisConfig, deg: Double): Future[SubmitResponse] =
    submitToHcd(a, hcdSetup(
      Hcd.PositionWheelCommand.commandName,
      Hcd.PositionWheelCommand.axisKey.set(Choice(a.galilChannel)),
      Hcd.PositionWheelCommand.positionKey.set(deg.toFloat)
    ), wheelMoveTimeout(a))

  /** Absolute motor move (engineering): HCD positionAxis(axis, target=counts). */
  protected def positionMotorCounts(a: AxisConfig, counts: Double): Future[SubmitResponse] =
    submitToHcd(a, hcdSetup(
      Hcd.PositionAxisCommand.commandName,
      Hcd.PositionAxisCommand.axisKey.set(Choice(a.galilChannel)),
      Hcd.PositionAxisCommand.targetKey.set(counts.toFloat)
    ), wheelMoveTimeout(a))

  /** Latest known wheel angle in degrees (0.0 if no snapshot yet). Accurate at
   *  command intake (axis idle), so a RELATIVE engineering move resolves to an
   *  absolute target here for a faithful recovery resend. */
  protected def currentAngleDeg(a: AxisConfig): Double =
    latestAxis.get(a.name).map(_.angularPositionDeg).getOrElse(0.0)

  /** Latest known motor position in counts (0.0 if no snapshot yet). */
  protected def currentCounts(a: AxisConfig): Double =
    latestAxis.get(a.name).map(_.positionCounts).getOrElse(0.0)

  // ---- snapshot enrichment: achieved slot + angle -------------------------

  /** Fold the wheel-specific CurrentStateAxis fields into the snapshot.
   *  wheelPosition is the embedded-reported achieved slot (-1 while a select is in
   *  progress); angularPosition is the wheel angle in degrees. */
  override protected def enrichAxisSnapshot(snap: AxisSnapshot, curr: CurrentState): AxisSnapshot =
    val k = Hcd.CurrentStateAxisACurrentState
    snap.copy(
      wheelPositionNum   = if curr.exists(k.wheelPositionKey) then curr(k.wheelPositionKey).head else snap.wheelPositionNum,
      angularPositionDeg = if curr.exists(k.angularPositionKey) then curr(k.angularPositionKey).head.toDouble else snap.angularPositionDeg
    )

  // ---- §7 common wheel command param read-keys ----------------------------
  // Built locally (matched to the generated keys by name + type) so the base can
  // read positionWheel/positionMotor params for ANY wheel without depending on a
  // specific component's generated Keys object. (APT's positionNumber tops out at
  // 4; the broader 1..7 set here is a superset and reads its values fine.)

  private val positionNumberKey    = ChoiceKey.make("positionNumber", "1", "2", "3", "4", "5", "6", "7")
  private val positioningMethodKey = ChoiceKey.make("positioningMethod", "ABSOLUTE", "RELATIVE")
  private val positionTargetKey    = ChoiceKey.make("positionTarget", "WHEEL", "MOTOR")
  private val wheelPositionDegKey  = FloatKey.make("wheelPosition", Units.degree)
  private val motorPositionKey     = IntKey.make("motorPosition", Units.count)

  // ---- select-command hooks (subclass supplies the mechanism specifics) ---

  /** The mechanism-specific select command name (e.g. "selectFilter"). */
  protected def selectCommandName: String

  /** Validate the select command's optic argument (subclass knows its key/choices). */
  protected def validateSelectCommand(runId: Id, s: Setup): ValidateCommandResponse

  /** Resolve the select command's optic name to a wheel slot (1..N) via the Wheel
   *  Position N Assignment config. Left(msg) if the optic is unassigned. */
  protected def resolveSelectSlot(s: Setup): Either[String, Int]

  // ---- validation ---------------------------------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case n if n == selectCommandName => validateSelectCommand(runId, s)
      case "positionWheel" =>
        if s.exists(positionNumberKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("positionWheel requires positionNumber"))
      case "positionMotor" =>
        val hasMethodTarget = s.exists(positioningMethodKey) && s.exists(positionTargetKey)
        val hasValue        = s.exists(wheelPositionDegKey) || s.exists(motorPositionKey)
        if hasMethodTarget && hasValue then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(
          "positionMotor requires positioningMethod, positionTarget and a wheelPosition or motorPosition"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- dispatch -----------------------------------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    val axisOpt = axes.headOption
    (s.commandName.name, axisOpt) match
      case (_, None) =>
        () => Future.successful(Error(runId, s"$assemblyPrefix has no configured wheel axis"))

      case (n, Some(a)) if n == selectCommandName =>
        resolveSelectSlot(s) match
          case Right(slot) =>
            log.info(s"$assemblyPrefix: $selectCommandName -> slot $slot")
            () => selectWheelSlot(a, slot)
          case Left(msg) =>
            () => Future.successful(Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue(msg)))

      case ("positionWheel", Some(a)) =>
        val slot = s(positionNumberKey).head.name.toInt
        log.info(s"$assemblyPrefix: positionWheel -> slot $slot")
        () => selectWheelSlot(a, slot)

      case ("positionMotor", Some(a)) =>
        val method = s(positioningMethodKey).head.name
        val target = s(positionTargetKey).head.name
        (target, method) match
          case ("WHEEL", _) =>
            val delta = if s.exists(wheelPositionDegKey) then s(wheelPositionDegKey).head.toDouble else 0.0
            val deg   = if method == "ABSOLUTE" then delta else currentAngleDeg(a) + delta
            log.info(s"$assemblyPrefix: positionMotor WHEEL $method $delta deg -> target $deg deg")
            () => positionWheelDeg(a, deg)
          case ("MOTOR", _) =>
            val delta  = if s.exists(motorPositionKey) then s(motorPositionKey).head.toDouble else 0.0
            val counts = if method == "ABSOLUTE" then delta else currentCounts(a) + delta
            log.info(s"$assemblyPrefix: positionMotor MOTOR $method $delta counts -> target $counts counts")
            () => positionMotorCounts(a, counts)
          case (other, _) =>
            () => Future.successful(Error(runId, s"positionMotor: unknown positionTarget $other"))

      case (other, _) =>
        () => Future.successful(Error(runId, s"unsupported command: $other"))

  // ---- moveToDefaultPosition: select the configured default slot ----------

  /** For a wheel, "default position" is the default slot NUMBER (SDD Table 7-1
   *  DefaultPosition = "The default wheel position number"). */
  override protected def runMoveToDefault(): Future[SubmitResponse] =
    submitAllAxes { a => selectWheelSlot(a, a.defaultPositionMm.toInt) }