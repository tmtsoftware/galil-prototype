package csw.proto.galil.hcd

/**
 * Bounded, columnar ring buffer of per-scan axis positions (ADR-002).
 *
 * WHY THIS EXISTS.  The HMI's engineering plot needs a dense position trace, and no
 * such trace exists anywhere else in the system.  The archived path is
 * HCD `CurrentState` -> assembly subscription -> assembly `SystemEvent` -> DMS, and
 * `MotionAssemblyHandlers.throttledPublish()` is a 1 Hz leading-edge throttle: during a
 * move at the 10 Hz scan rate roughly nine of every ten real samples never reach DMS.
 * The ICD marks every `CurrentState*` item `Archive: no`.  This buffer holds the
 * controller-level truth, in raw encoder counts, at full acquisition rate.
 *
 * WHERE SAMPLES COME FROM.  `ControllerStatusActor.handleQRResponse` records exactly one
 * sample per QR scan, for all axes at once, stamped with the scan's own wall-clock time.
 * That is the acquisition instant -- deliberately not the publication instant, which is
 * a fixed 10 Hz resample of IS state and therefore duplicates at the 1 Hz standby rate
 * and beats against the scan at the 10 Hz action rate.
 *
 * THREADING.  `ControllerStatusActor` writes from its actor thread; `HmiServer` reads
 * from HTTP handler threads and from its 4 Hz push scheduler.  All access is guarded by
 * `synchronized` on this instance.
 *
 * The write path runs on the QR scan thread, which is hot and latency sensitive
 * (ADR-002 section 3).  `record` is therefore O(axisCount), allocation free, and holds
 * the lock only long enough to store primitives into preallocated arrays.  Callers pass
 * a reusable scratch array; values are copied, so the caller may reuse it immediately.
 * All allocation happens on the read side, which is not on any hot path.
 *
 * LAYOUT.  Columnar: one shared `Array[Long]` of sample times and one `Array[Double]`
 * per axis, all indexed by the same ring position.  Every axis in a sample shares one
 * timestamp, which is what makes cross-axis timing comparison meaningful rather than
 * approximate, and it makes aligned CSV export fall out for free.  Adding a second
 * recorded signal later (positionError, velocity) is an added column, not a redesign.
 *
 * ABSENT AXES.  An axis not present in a scan is stored as `Double.NaN` rather than
 * carrying a parallel presence array.  Encoders map NaN to JSON `null` / an empty CSV
 * cell, so "not configured on this controller" is distinguishable from "at zero".
 *
 * CURSORS AND THE RETENTION WINDOW.  Every sample has a monotonically increasing
 * sequence number (`totalRecorded` at the time it was written).  Consumers pass the
 * `nextSeq` from their previous snapshot to receive only what is new, which is how the
 * HMI delivers full scan-rate fidelity over a 4 Hz WebSocket push without raising the
 * frame rate.  Sequence numbers are never reused and do not wrap in any realistic
 * runtime (a 10 Hz scan needs ~29 billion years to exhaust `Long`).
 *
 * The window is sample bounded, not time bounded (ADR-002 section 4).  `DefaultCapacity`
 * is 300 s at the 10 Hz action rate, but the same 3000 samples span ~50 minutes at the
 * 1 Hz standby rate -- history is cheapest exactly when the mechanism is idle.  The
 * consequence is that the window's DURATION varies, so any display must label the span
 * it actually holds rather than implying a fixed one.
 */
final class PositionHistoryBuffer(val capacity: Int = PositionHistoryBuffer.DefaultCapacity) {
  require(capacity > 0, s"PositionHistoryBuffer capacity must be positive, got $capacity")

  import PositionHistoryBuffer._

  /** Shared time column; `times(i)` is the scan time for every axis at ring index i. */
  private val times = new Array[Long](capacity)

  /** `positions(axisIndex)(ringIndex)`; NaN where the axis was absent from that scan. */
  private val positions: Array[Array[Double]] = Array.fill(AxisCount)(Array.fill(capacity)(Double.NaN))

  /** Next ring slot to write. */
  private var writeIdx: Int = 0

  /** Samples ever recorded.  Also the sequence number the next sample will receive. */
  private var totalRecorded: Long = 0L

  /**
   * Append one scan's worth of positions.
   *
   * @param timeMs      the scan's wall-clock time (`System.currentTimeMillis`), shared by
   *                    every axis in this sample.  UTC rather than TAI so plot samples
   *                    correlate directly with CSW log timestamps (S86 precedent).
   * @param axisValues  positions indexed by `Axis.index`; `Double.NaN` for an axis not
   *                    present in this scan.  Read-only here: values are copied out, so
   *                    the caller may reuse the array on the next scan.  Shorter arrays
   *                    are tolerated (missing tail axes are recorded as absent) so a
   *                    future controller with a different axis count cannot throw on the
   *                    scan path.
   */
  def record(timeMs: Long, axisValues: Array[Double]): Unit = synchronized {
    times(writeIdx) = timeMs
    var a = 0
    while (a < AxisCount) {
      positions(a)(writeIdx) = if (a < axisValues.length) axisValues(a) else Double.NaN
      a += 1
    }
    writeIdx = writeIdx + 1
    if (writeIdx == capacity) writeIdx = 0
    totalRecorded += 1L
  }

  /** Sequence number the next recorded sample will receive; also the count ever recorded. */
  def nextSequence: Long = synchronized(totalRecorded)

  /** Number of samples currently retained (<= capacity). */
  def size: Int = synchronized(retainedCount)

  /** Sequence number of the oldest retained sample. */
  def oldestSequence: Long = synchronized(oldestSeqUnsafe)

  /**
   * Copy out every retained sample with sequence number >= `sinceSeq`.
   *
   * Pass 0 for the whole retained window (panel open, CSV export); pass the previous
   * snapshot's `nextSeq` for an incremental read (the WebSocket delta frame).
   *
   * If `sinceSeq` names a sample that has already been evicted, the snapshot silently
   * starts at the oldest retained sample and sets `gap = true` -- the consumer is told
   * it missed data rather than being handed a trace with an invisible discontinuity.
   */
  def snapshot(sinceSeq: Long = 0L): Snapshot = synchronized {
    val oldest = oldestSeqUnsafe
    val from   = math.max(sinceSeq, oldest)
    val gap    = sinceSeq < oldest
    val n      = math.max(0L, totalRecorded - from).toInt

    if (n == 0) {
      // No `return` here: this block is a closure passed to `synchronized`, so an early
      // return would compile to a non-local return implemented by throwing.
      Snapshot(from, totalRecorded, EmptyTimes, Array.fill(AxisCount)(EmptyPositions), gap)
    } else {
      val outTimes = new Array[Long](n)
      val outPos   = Array.ofDim[Double](AxisCount, n)

      // `from` is retained, so its ring slot is (from % capacity); walk forward n slots.
      var i = 0
      var ring = (from % capacity).toInt
      while (i < n) {
        outTimes(i) = times(ring)
        var a = 0
        while (a < AxisCount) {
          outPos(a)(i) = positions(a)(ring)
          a += 1
        }
        i += 1
        ring += 1
        if (ring == capacity) ring = 0
      }

      Snapshot(from, totalRecorded, outTimes, outPos, gap)
    }
  }

  // No clear()/reset(): GalilHcd constructs the buffer in initialize(), and a CSW
  // Restart re-runs initialize() against a fresh TLA, so a restarted HCD gets a new
  // instance rather than a wiped one.  Adding a reset path would create a second way
  // for the buffer to become empty that consumers would have to reason about.

  private def retainedCount: Int =
    math.min(capacity.toLong, totalRecorded).toInt

  private def oldestSeqUnsafe: Long =
    math.max(0L, totalRecorded - capacity)
}

object PositionHistoryBuffer {

  /** DMC-500x0 controllers expose at most 8 axes; the buffer is sized for the maximum
   *  so a controller's configured axis set can change without reallocating. */
  val AxisCount: Int = Axis.values.length

  /** 300 s at the 10 Hz action polling rate; ~50 minutes at the 1 Hz standby rate.
   *  8 axes x 3000 doubles + a shared 3000-entry time column is under 220 KB. */
  val DefaultCapacity: Int = 3000

  private val EmptyTimes: Array[Long]       = Array.empty[Long]
  private val EmptyPositions: Array[Double] = Array.empty[Double]

  /**
   * An immutable copy of part of the buffer.
   *
   * @param firstSeq sequence number of `times(0)`; equals the requested `sinceSeq`
   *                 unless eviction forced it forward (see `gap`)
   * @param nextSeq  pass as the next call's `sinceSeq` to continue without overlap
   * @param times    scan times, oldest first
   * @param positions `positions(axisIndex)(i)` pairs with `times(i)`; NaN = axis absent
   * @param gap      true when the requested `sinceSeq` had already been evicted, so
   *                 samples between it and `firstSeq` are permanently lost
   */
  final case class Snapshot(
    firstSeq: Long,
    nextSeq: Long,
    times: Array[Long],
    positions: Array[Array[Double]],
    gap: Boolean
  ) {
    /** Number of samples in this snapshot. */
    def size: Int = times.length

    /** Wall-clock span covered, in milliseconds; 0 for fewer than two samples.  Callers
     *  should display this rather than assuming a fixed window (the retention window is
     *  sample bounded, so its duration varies with the polling rate). */
    def spanMs: Long = if (times.length < 2) 0L else times(times.length - 1) - times(0)
  }
}
