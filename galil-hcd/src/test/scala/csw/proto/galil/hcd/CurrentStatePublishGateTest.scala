package csw.proto.galil.hcd

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for the per-axis publication gate in [[CurrentStatePublisherActor]] (S88).
 *
 * These cover the DECISION only -- no actor, no services, no clock -- which is why they
 * are in their own suite rather than in `CurrentStatePublisherActorTest`, which spins up
 * a FrameworkTestKit and a real HCD for every test it contains.
 *
 * What the gate is for: the QR poll rate is GLOBAL, rising to 10 Hz whenever any axis is
 * active. Publishing every axis on every scan would therefore push seven idle axes to
 * 10 Hz because the eighth is moving. The gate makes "more while moving, fewer while
 * idle" true per axis instead of per controller.
 */
class CurrentStatePublishGateTest extends AnyFunSuite with Matchers {

  import CurrentStatePublisherActor.{IdleRepublishMs, isAxisActive, shouldPublishAxis}

  private def axis(state: AxisStateEnum): AxisState = AxisState(axisState = state)

  // ── which states count as active ──────────────────────────────────────

  test("Moving, Homing and Tracking are active; Idle, Lost and Error are not") {
    isAxisActive(axis(AxisStateEnum.Moving))   shouldBe true
    isAxisActive(axis(AxisStateEnum.Homing))   shouldBe true
    isAxisActive(axis(AxisStateEnum.Tracking)) shouldBe true

    isAxisActive(axis(AxisStateEnum.Idle))     shouldBe false
    isAxisActive(axis(AxisStateEnum.Lost))     shouldBe false
    // Error is deliberately NOT active: a faulted axis is not producing motion, so it
    // does not warrant scan-rate telemetry.  The transition INTO Error is still never
    // dropped -- that arrives as an axisState change, which the gate honours below.
    isAxisActive(axis(AxisStateEnum.Error))    shouldBe false
  }

  // ── the three reasons to publish ──────────────────────────────────────

  test("an active axis publishes on every scan, however recently it last published") {
    shouldPublishAxis(axisActive = true, axisStateChanged = false, elapsedMs = 0L) shouldBe true
    shouldPublishAxis(axisActive = true, axisStateChanged = false, elapsedMs = 1L) shouldBe true
  }

  test("an axisState transition always publishes, even on an idle axis just published") {
    // The reason this clause exists: a fast command (~100 ms) can pass through Moving
    // entirely between two idle republishes.  Without this, an assembly watching for
    // Idle -> Moving -> Idle would never observe the move at all.
    shouldPublishAxis(axisActive = false, axisStateChanged = true, elapsedMs = 0L) shouldBe true
  }

  test("an idle axis is throttled until the idle floor elapses") {
    shouldPublishAxis(axisActive = false, axisStateChanged = false, elapsedMs = 0L)  shouldBe false
    shouldPublishAxis(axisActive = false, axisStateChanged = false, elapsedMs = 100L) shouldBe false
    shouldPublishAxis(axisActive = false, axisStateChanged = false, elapsedMs = IdleRepublishMs - 1) shouldBe false
    shouldPublishAxis(axisActive = false, axisStateChanged = false, elapsedMs = IdleRepublishMs)     shouldBe true
    shouldPublishAxis(axisActive = false, axisStateChanged = false, elapsedMs = IdleRepublishMs + 1) shouldBe true
  }

  // ── the property the floor exists to guarantee ────────────────────────

  test("the idle floor is below the 1 Hz standby scan period") {
    // If the floor were >= 1000 ms, jitter around the 1 Hz standby scan would fail the
    // check on roughly every other scan and halve the idle publication rate to ~0.5 Hz.
    // This is the regression this constant exists to prevent, so assert it directly
    // rather than trusting the comment beside it.
    IdleRepublishMs should be < 1000L
  }

  test("an idle axis is throttled to roughly 1 Hz even while the poller runs at 10 Hz") {
    // Simulate the case the gate exists for: this axis is idle, but ANOTHER axis is
    // moving, so scans arrive every 100 ms.  Count how many of 30 scans (3 s) publish.
    var lastPublishMs = 0L
    var published     = 0
    var nowMs         = 0L
    (1 to 30).foreach { _ =>
      nowMs += 100L
      if (shouldPublishAxis(axisActive = false, axisStateChanged = false, elapsedMs = nowMs - lastPublishMs)) {
        published += 1
        lastPublishMs = nowMs
      }
    }
    // 3 s at the idle floor => 3 publishes, not the 30 the old fixed 10 Hz timer produced.
    published shouldBe 3
  }

  test("an active axis publishes on all of those same scans") {
    var published = 0
    (1 to 30).foreach { _ =>
      if (shouldPublishAxis(axisActive = true, axisStateChanged = false, elapsedMs = 0L)) published += 1
    }
    published shouldBe 30
  }

  test("a standby-rate scan train publishes every scan despite jitter") {
    // 1 Hz scans with +/-60 ms of jitter: every one must publish, or the idle rate
    // silently halves.  Jitter is deterministic here, not random, so the test cannot
    // flake.
    val jitter = Seq(1000L, 940L, 1060L, 980L, 1020L, 941L, 1059L)
    var lastPublishMs = 0L
    var nowMs         = 0L
    var published     = 0
    jitter.foreach { dt =>
      nowMs += dt
      if (shouldPublishAxis(axisActive = false, axisStateChanged = false, elapsedMs = nowMs - lastPublishMs)) {
        published += 1
        lastPublishMs = nowMs
      }
    }
    published shouldBe jitter.length
  }
}
