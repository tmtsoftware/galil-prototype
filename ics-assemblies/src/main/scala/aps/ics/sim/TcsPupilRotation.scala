package aps.ics.sim

import csw.params.core.generics.Key
import csw.params.core.generics.KeyType.{DoubleKey, TAITimeKey}
import csw.params.events.{EventKey, EventName}
import csw.params.core.models.Units
import csw.prefix.models.{Prefix, Subsystem}
import csw.time.core.models.TAITime

/**
 * The TCS `PupilRotation` event contract — the single source of truth shared by the
 * TCS simulator publisher ([[TcsPupilRotationSimApp]]) and the K-Mirror assembly's
 * Tracking Control Actor subscription. Modelled on the TCS subscribe-model schema:
 *
 *   subsystem = TCS, name = PupilRotation, maxRate = 1 (1 Hz)
 *     rotation       : double, degree  — pupil rotation target
 *     rotationRate   : double, deg/sec — pupil rotation rate target
 *     validTime      : TAITime         — the instant (rotation, rotationRate) apply;
 *                                        maps 1:1 to the HCD trackAxis validTime
 *
 * PROVISIONAL: the real TCS internals are TBD. The component name `PointingKernel`
 * stands in for the eventual TCS source (the schema's "TCS APS Assembly" was an APS
 * convenience that does not survive into a CSW component name). It is centralised
 * here so swapping to the real TCS prefix later is a one-line change.
 *
 * NOTE on units: CSW has no compound degree/second unit, so `rotationRate` carries
 * `Units.degree` and is documented as per-second. (The inherited schema marked it
 * NoUnits; deg/s is the intended meaning.)
 */
object TcsPupilRotation:

  /** PROVISIONAL TCS source prefix — change here only when the real TCS component is fixed. */
  val prefix: Prefix       = Prefix(Subsystem.TCS, "PointingKernel")
  val eventName: EventName = EventName("PupilRotation")
  val eventKey: EventKey   = EventKey(prefix, eventName)

  val rotationKey: Key[Double]     = DoubleKey.make("rotation", Units.degree)
  val rotationRateKey: Key[Double] = DoubleKey.make("rotationRate", Units.degree) // per second
  val validTimeKey: Key[TAITime]   = TAITimeKey.make("validTime")
