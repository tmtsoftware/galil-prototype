package aps.ics.assembly.common

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import csw.command.api.scaladsl.CommandService
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, Setup}
import csw.params.core.generics.GChoiceKey
import csw.params.core.generics.KeyType._
import csw.params.core.models.Id
import csw.params.core.states.{CurrentState, StateName}

import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion` as Hcd

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/**
 * Common base for APS-ICS PUPIL-MASK WHEEL assemblies (SDD §7) — the PSH and PIT
 * pupil-mask wheels. Specialises [[WheelAssemblyHandlers]] with the two things a
 * pupil-mask wheel adds over a plain filter wheel:
 *
 *   1. The mechanism-specific SELECT command is `selectPupilMask` (resolves an
 *      optic NAME to a slot via the Wheel Position N Assignment config, exactly
 *      like a filter wheel's selectFilter). Both pupil-mask wheels share the
 *      command name; they differ only in the pupilMask choice set, which the
 *      concrete supplies via [[opticKey]].
 *
 *   2. A wheel DETENT — an engineering/diagnostic mechanism with its own command
 *      and telemetry:
 *        - `commandDetent(EXTENDED|RETRACTED)` drives the detent solenoid via the
 *          HCD `setBit` primitive (SB/CB at a configured digital-output address).
 *        - `detentState` (RETRACTED | EXTENDED | OUT OF POSITION) is decoded from
 *          the HCD `InputOutputState` digital inputs at two configured sensor
 *          addresses, and folded into the concrete's axisStatus telemetry via
 *          [[currentDetentState]].
 *
 * Note on the OPERATIONAL path: a normal `selectPupilMask` does NOT touch the
 * detent here. The HCD's embedded #Select program owns the detent during a select
 * and only reports the achieved slot (CurrentStateAxis.wheelPosition) once the
 * detent is engaged and the sensors confirm it; the assembly judges select
 * success/failure on the HCD's slot-based inPosition, exactly as for filter
 * wheels. The detent COMMAND here is for daytime engineering work only.
 *
 * Detent I/O addresses (SDD §7 detent DIO) are read from config. They are
 * currently DUMMY/PROVISIONAL placeholders — the detent solenoid and limit-switch
 * sensors are not yet wired — so the commands are exercisable end-to-end (in the
 * simulator and on hardware) before the controller-1 GPIO/RIO wiring is finalised.
 * Addresses are Galil 1-based (matching `setBit`/SB/CB); the published
 * digitalInputs array is 0-based, so a Galil address N reads array index N-1.
 *
 * Single-axis, like all wheels: helpers operate on axes.headOption.
 */
abstract class PupilMaskWheelAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends WheelAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  // ---- select-command: selectPupilMask (optic name -> slot) ---------------

  override protected def selectCommandName: String = "selectPupilMask"

  /** The generated pupilMask choice key for this concrete wheel. PSH and PIT have
   *  different mask choice sets, so the concrete supplies its own generated key;
   *  reads and validation then use the right name, type and choices. */
  protected def opticKey: GChoiceKey

  /** The HOCON object key for this wheel's single axis (e.g. "pupilMaskWheel"). */
  private def axisKey: String = axisConfigKeys.headOption.getOrElse("pupilMaskWheel")

  /** optic name -> slot, from `<axis>.positionAssignments` (slot -> name). */
  private def opticToSlot: Map[String, Int] =
    val path = s"$axisKey.positionAssignments"
    if componentConfig.hasPath(path) then
      val pa = componentConfig.getConfig(path)
      pa.root().keySet().asScala.flatMap { slotStr =>
        slotStr.toIntOption.map(slot => pa.getString(slotStr) -> slot)
      }.toMap
    else Map.empty

  override protected def resolveSelectSlot(s: Setup): Either[String, Int] =
    val mask = s(opticKey).head.name
    opticToSlot.get(mask).toRight(s"pupil mask '$mask' has no assigned wheel position")

  override protected def validateSelectCommand(runId: Id, s: Setup): ValidateCommandResponse =
    if !s.exists(opticKey) then
      Invalid(runId, CommandIssue.MissingKeyIssue("selectPupilMask requires pupilMask"))
    else
      resolveSelectSlot(s) match
        case Right(_)  => Accepted(runId)
        case Left(msg) => Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue(msg))

  // ---- detent config (DUMMY / PROVISIONAL — Galil 1-based DIO addresses) ---

  private def detentInt(key: String, default: Int): Int =
    val path = s"$axisKey.detent.$key"
    if componentConfig.hasPath(path) then componentConfig.getInt(path) else default

  /** Digital OUTPUT address driving the detent solenoid (SB extends / CB retracts). */
  protected def detentOutputBit: Int      = detentInt("outputBit", 1)

  /** Digital INPUT address, true when the detent is fully EXTENDED. */
  protected def detentExtendedInput: Int  = detentInt("extendedInput", 1)

  /** Digital INPUT address, true when the detent is fully RETRACTED. */
  protected def detentRetractedInput: Int = detentInt("retractedInput", 2)

  // ---- commandDetent: validate + dispatch (extends the wheel command set) --

  private val detentPositionKey = ChoiceKey.make("position", "EXTENDED", "RETRACTED")

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "commandDetent" =>
        if s.exists(detentPositionKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("commandDetent requires position"))
      case _ => super.validateSpecificCommand(runId, s)

  override protected def handleSpecificCommand(runId: Id, s: Setup): () => Future[SubmitResponse] =
    if s.commandName.name == "commandDetent" then
      axes.headOption match
        case None =>
          () => Future.successful(Error(runId, s"$assemblyPrefix has no configured wheel axis"))
        case Some(a) =>
          val extended = s(detentPositionKey).head.name == "EXTENDED"
          val value    = if extended then 1 else 0
          log.info(s"$assemblyPrefix: commandDetent -> ${if extended then "EXTENDED" else "RETRACTED"} " +
            s"(setBit $detentOutputBit=$value)")
          () => setDetent(a, value)
    else super.handleSpecificCommand(runId, s)

  /** Drive the detent solenoid: HCD setBit(address=detentOutputBit, value). The HCD
   *  turns this into SB (value!=0) or CB (value==0). setBit is controller-global
   *  (no axis argument); `a` only selects the HCD that owns this wheel. */
  protected def setDetent(a: AxisConfig, value: Int): Future[SubmitResponse] =
    submitToHcd(a, hcdSetup(
      Hcd.SetBitCommand.commandName,
      Hcd.SetBitCommand.addressKey.set(detentOutputBit),
      Hcd.SetBitCommand.valueKey.set(value)
    ), configHcdTimeout)

  // ---- detent sensor readback (HCD InputOutputState digital inputs) --------

  /** Latest digital-input bits from the HCD InputOutputState (0-based array;
   *  Galil address N reads index N-1). Single writer = the subscription thread. */
  @volatile protected var latestDigitalInputs: Array[Boolean] = Array.empty

  /** Subscribe to the owning HCD's InputOutputState for the detent sensor bits. */
  override protected def subscribeExtra(hcdPrefix: String, cs: CommandService): Unit =
    if axes.headOption.exists(_.galilHcd == hcdPrefix) then
      cs.subscribeCurrentState(
        Set(StateName(Hcd.InputOutputStateCurrentState.eventKey.eventName.name)),
        curr => onInputOutputState(curr)
      )

  private def onInputOutputState(curr: CurrentState): Unit =
    val k = Hcd.InputOutputStateCurrentState.digitalInputsKey
    if curr.exists(k) then
      val bits = curr(k).values
      if !java.util.Arrays.equals(bits, latestDigitalInputs) then
        latestDigitalInputs = bits
        // detentState changed; surface promptly (detent moves are rare engineering
        // actions, so an un-throttled publish here is cheap).
        publishTelemetry()

  /** Decode the two detent sensor inputs into the ICD detentState enum
   *  (EXTENDED | RETRACTED | OUT OF POSITION). Both sensors false, or both true,
   *  reads OUT OF POSITION. With the dummy/unwired sensor addresses this reads
   *  OUT OF POSITION until the detent limit switches are wired. */
  protected def currentDetentState: String =
    val ext = inputBit(detentExtendedInput)
    val ret = inputBit(detentRetractedInput)
    if ext && !ret then "EXTENDED"
    else if ret && !ext then "RETRACTED"
    else "OUT OF POSITION"

  private def inputBit(galilAddress1Based: Int): Boolean =
    val idx = galilAddress1Based - 1
    idx >= 0 && idx < latestDigitalInputs.length && latestDigitalInputs(idx)
