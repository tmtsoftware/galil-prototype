package aps.ics.assembly.common

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.Timeout
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.params.commands.CommandResponse._
import csw.params.core.models.Choice

import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion` as Hcd

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Linear stage assemblies (SDD §6). A LINEAR mechanism whose moves are expressed
 * in millimetres. Specialises [[MotionAssemblyHandlers]] with the mm<->counts move
 * helpers, the travel-based move timeout, and the linear default-position move
 * (an absolute mm position). The native unit is millimetres, so `countsPerUnit`
 * is the assembly-owned mm scale.
 *
 * The ten §6 stage assemblies extend this unchanged — they supply only their
 * config resource, axis keys, specific command(s), and telemetry.
 */
abstract class StageAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends MotionAssemblyHandlers(ctx, cswCtx):

  /** Native unit is millimetres: counts-per-unit is the assembly-owned mm scale. */
  override protected def countsPerUnit(a: AxisConfig): Double = a.countsPerMm

  /** Backstop wait for a move: worst-case full-travel time x2 + margin, floored.
   *  Position-independent and >= the HCD's distance-scaled positionAxis watchdog. */
  protected def moveHcdTimeout(a: AxisConfig): Timeout =
    val v        = if a.velocity > 0 then a.velocity else 1.0
    val travelMm = math.abs(a.upperLimitMm - a.lowerLimitMm)
    Timeout(math.max(10.0, (travelMm / v) * 2.0 + 5.0).seconds)

  /** moveToDefaultPosition: absolute mm move to each axis's configured default. */
  override protected def runMoveToDefault(): Future[SubmitResponse] =
    submitAllAxes { a => positionAxisMm(a, a.defaultPositionMm) }

  /** Latest known axis position in mm (0.0 if no snapshot yet). Accurate at
   *  command intake because commands are only accepted while the axis is idle
   *  (the command-state gate rejects while Processing/ErrorRecovery), so the
   *  position is settled and not changing. */
  protected def currentPositionMm(a: AxisConfig): Double =
    latestAxis.get(a.name).map(s => a.countsToMm(s.positionCounts)).getOrElse(0.0)

  /** Absolute move: mm -> counts -> HCD positionAxis. */
  protected def positionAxisMm(a: AxisConfig, targetMm: Double): Future[SubmitResponse] =
    submitToHcd(a, hcdSetup(
      Hcd.PositionAxisCommand.commandName,
      Hcd.PositionAxisCommand.axisKey.set(Choice(a.galilChannel)),
      Hcd.PositionAxisCommand.targetKey.set(a.mmToCounts(targetMm).toFloat)
    ), moveHcdTimeout(a))

  /** Relative move: mm -> counts -> HCD offsetAxis. */
  protected def offsetAxisMm(a: AxisConfig, distanceMm: Double): Future[SubmitResponse] =
    submitToHcd(a, hcdSetup(
      Hcd.OffsetAxisCommand.commandName,
      Hcd.OffsetAxisCommand.axisKey.set(Choice(a.galilChannel)),
      Hcd.OffsetAxisCommand.distanceKey.set(a.mmToCounts(distanceMm).toFloat)
    ), moveHcdTimeout(a))