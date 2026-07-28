package csw.proto.galil.hcd

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for [[PositionHistoryBuffer]] (ADR-002).
 *
 * The buffer is pure data structure -- no actors, no clock -- so everything about it is
 * directly testable.  The properties worth pinning down are the ones a plot would
 * silently misrender if they broke: ring wraparound, cursor arithmetic across eviction,
 * NaN-vs-zero for absent axes, and index alignment between the time column and the axis
 * columns.
 */
class PositionHistoryBufferTest extends AnyFunSuite with Matchers {

  /** Build a scratch row the way ControllerStatusActor does: NaN everywhere, then set
   *  the axes actually present in the scan. */
  private def row(values: (Int, Double)*): Array[Double] = {
    val a = Array.fill(PositionHistoryBuffer.AxisCount)(Double.NaN)
    values.foreach { case (idx, v) => a(idx) = v }
    a
  }

  test("an empty buffer yields an empty snapshot and a zero cursor") {
    val buf = new PositionHistoryBuffer(10)
    buf.size shouldBe 0
    buf.nextSequence shouldBe 0L

    val snap = buf.snapshot()
    snap.size shouldBe 0
    snap.nextSeq shouldBe 0L
    snap.gap shouldBe false
    snap.spanMs shouldBe 0L
  }

  test("records land in order with their scan time and per-axis values") {
    val buf = new PositionHistoryBuffer(10)
    buf.record(1000L, row(0 -> 10.0, 1 -> 20.0))
    buf.record(1100L, row(0 -> 11.0, 1 -> 21.0))

    val snap = buf.snapshot()
    snap.size shouldBe 2
    snap.times.toSeq shouldBe Seq(1000L, 1100L)
    snap.positions(0).toSeq shouldBe Seq(10.0, 11.0)
    snap.positions(1).toSeq shouldBe Seq(20.0, 21.0)
    snap.firstSeq shouldBe 0L
    snap.nextSeq shouldBe 2L
    snap.spanMs shouldBe 100L
  }

  test("an axis absent from a scan is NaN, not zero") {
    // This is the difference between "axis C is not configured on this controller" and
    // "axis C is sitting at the origin" -- the latter would draw a real-looking trace.
    val buf = new PositionHistoryBuffer(10)
    buf.record(1000L, row(0 -> 10.0))

    val snap = buf.snapshot()
    snap.positions(0)(0) shouldBe 10.0
    snap.positions(2)(0).isNaN shouldBe true
  }

  test("a short scratch row records the missing tail axes as absent rather than throwing") {
    // Defensive: the scan path must not be able to throw on an unexpected axis count.
    val buf = new PositionHistoryBuffer(4)
    buf.record(1000L, Array(5.0, 6.0))

    val snap = buf.snapshot()
    snap.positions(0)(0) shouldBe 5.0
    snap.positions(1)(0) shouldBe 6.0
    snap.positions(2)(0).isNaN shouldBe true
  }

  test("the ring wraps, retaining exactly the most recent `capacity` samples in order") {
    val buf = new PositionHistoryBuffer(3)
    (1 to 5).foreach(i => buf.record(1000L + i, row(0 -> i.toDouble)))

    buf.size shouldBe 3
    buf.nextSequence shouldBe 5L
    buf.oldestSequence shouldBe 2L

    val snap = buf.snapshot()
    snap.size shouldBe 3
    snap.times.toSeq shouldBe Seq(1003L, 1004L, 1005L)
    snap.positions(0).toSeq shouldBe Seq(3.0, 4.0, 5.0)
    snap.firstSeq shouldBe 2L
  }

  test("snapshot(since) returns only newer samples, and its nextSeq chains without overlap") {
    // This is the WebSocket delta path: each 4Hz push asks for everything since the
    // previous push, so double-delivery or a skipped sample shows up directly here.
    val buf = new PositionHistoryBuffer(100)
    buf.record(1000L, row(0 -> 1.0))
    buf.record(1010L, row(0 -> 2.0))

    val first = buf.snapshot(0L)
    first.size shouldBe 2
    first.nextSeq shouldBe 2L

    // Nothing new yet.
    buf.snapshot(first.nextSeq).size shouldBe 0

    buf.record(1020L, row(0 -> 3.0))
    val second = buf.snapshot(first.nextSeq)
    second.size shouldBe 1
    second.firstSeq shouldBe 2L
    second.positions(0).toSeq shouldBe Seq(3.0)
    second.nextSeq shouldBe 3L
  }

  test("a cursor overtaken by eviction reports gap and resumes at the oldest retained sample") {
    // A slow or disconnected consumer must be TOLD it lost samples; silently resuming
    // would hand it a trace with an invisible discontinuity.
    val buf = new PositionHistoryBuffer(3)
    buf.record(1000L, row(0 -> 1.0))
    val cursor = buf.snapshot().nextSeq   // == 1

    (2 to 6).foreach(i => buf.record(1000L + i, row(0 -> i.toDouble)))   // evicts seq 1..3

    val snap = buf.snapshot(cursor)
    snap.gap shouldBe true
    snap.firstSeq shouldBe buf.oldestSequence
    snap.size shouldBe 3
    snap.positions(0).toSeq shouldBe Seq(4.0, 5.0, 6.0)
  }

  test("a cursor that is merely current reports no gap") {
    val buf = new PositionHistoryBuffer(3)
    buf.record(1000L, row(0 -> 1.0))
    val snap = buf.snapshot(buf.snapshot().nextSeq)
    snap.size shouldBe 0
    snap.gap shouldBe false
  }

  test("axis columns stay index-aligned with the time column across a wrap") {
    // Columnar storage only works if positions(a)(i) always pairs with times(i); a
    // mistake in the wrap arithmetic would shear one column against another.
    val buf = new PositionHistoryBuffer(4)
    (1 to 9).foreach { i =>
      buf.record(2000L + i, row(0 -> i.toDouble, 3 -> (i * 100).toDouble))
    }
    val snap = buf.snapshot()
    snap.size shouldBe 4
    var i = 0
    while (i < snap.size) {
      val t = snap.times(i)
      snap.positions(0)(i) shouldBe (t - 2000L).toDouble
      snap.positions(3)(i) shouldBe (t - 2000L).toDouble * 100.0
      i += 1
    }
  }

  test("the caller's scratch array may be reused between records") {
    // ControllerStatusActor keeps ONE scratch row and rewrites it every scan, so record
    // must copy values out rather than retaining the array.
    val buf = new PositionHistoryBuffer(10)
    val scratch = Array.fill(PositionHistoryBuffer.AxisCount)(Double.NaN)

    scratch(0) = 1.0
    buf.record(1000L, scratch)
    scratch(0) = 2.0
    buf.record(1010L, scratch)

    buf.snapshot().positions(0).toSeq shouldBe Seq(1.0, 2.0)
  }

  test("a non-positive capacity is rejected at construction") {
    an[IllegalArgumentException] should be thrownBy new PositionHistoryBuffer(0)
  }

  test("concurrent readers never observe a torn sample") {
    // CS writes from its actor thread while HmiServer reads from HTTP handler threads.
    // Every retained sample must be internally consistent: axis 1 is always 10x axis 0.
    val buf = new PositionHistoryBuffer(64)
    // AtomicReference rather than `@volatile var`: @volatile applies to fields, not to
    // locals, so it would not compile here -- and the reference genuinely crosses threads.
    val failure = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)

    val writer = new Thread(() => {
      var i = 1
      while (i <= 20000) {
        buf.record(i.toLong, row(0 -> i.toDouble, 1 -> (i * 10).toDouble))
        i += 1
      }
    })
    val reader = new Thread(() => {
      var n = 0
      while (n < 3000) {
        val snap = buf.snapshot()
        var j = 0
        while (j < snap.size) {
          val a0 = snap.positions(0)(j)
          val a1 = snap.positions(1)(j)
          if (!a0.isNaN && a1 != a0 * 10.0) failure.set(Some(s"torn sample: $a0 / $a1"))
          j += 1
        }
        n += 1
      }
    })

    writer.start(); reader.start()
    writer.join(); reader.join()

    failure.get() shouldBe None
    buf.nextSequence shouldBe 20000L
  }
}
