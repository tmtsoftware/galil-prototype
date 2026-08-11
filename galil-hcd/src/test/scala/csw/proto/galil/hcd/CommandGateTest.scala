package csw.proto.galil.hcd

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Tests for CommandGate — the shared, pure command-gating logic used by both
 * the CSW validate path (GalilHcd.validateCommand) and the HMI command path
 * (HmiServer.handleCommandRequest).
 *
 * These functions are pure (no actor, no IS query, no logging), so the suite
 * needs no ActorTestKit.  The intent is to lock down the gate decisions that
 * the two entry paths must agree on — in particular:
 *   - the Faulted exemption set differs by path (faultReset only for CSW;
 *     faultReset + setSoftLimits for HMI) and must be honoured exactly;
 *   - the rejection message strings are identical across paths;
 *   - the soft-limit absolute-target resolution differs by command
 *     (positionAxis = absolute, offsetAxis = position + distance).
 */
class CommandGateTest extends AnyFunSuite with Matchers:

  // Exemption sets as wired by each caller.
  private val cswFaultedExempt = Set("faultReset")
  private val hmiFaultedExempt = Set("faultReset", "setSoftLimits")

  // ========================================
  // checkHcdState — HCD lifecycle gate
  // ========================================

  test("checkHcdState: Ready permits any command on both paths"):
    val ready = HcdState(state = HcdStateEnum.Ready)
    CommandGate.checkHcdState(ready, "positionAxis", cswFaultedExempt) shouldBe None
    CommandGate.checkHcdState(ready, "positionAxis", hmiFaultedExempt) shouldBe None
    CommandGate.checkHcdState(ready, "faultReset", cswFaultedExempt) shouldBe None

  test("checkHcdState: Uninitialized rejects everything, no exemptions"):
    val uninit = HcdState(state = HcdStateEnum.Uninitialized)
    val expected = Some("HCD Uninitialized — commands not yet accepted")
    CommandGate.checkHcdState(uninit, "positionAxis", cswFaultedExempt) shouldBe expected
    // faultReset is NOT exempt during Uninitialized even though it is during Faulted
    CommandGate.checkHcdState(uninit, "faultReset", cswFaultedExempt) shouldBe expected
    // setSoftLimits is NOT exempt during Uninitialized on the HMI path either
    CommandGate.checkHcdState(uninit, "setSoftLimits", hmiFaultedExempt) shouldBe expected

  test("checkHcdState: Faulted rejects non-exempt commands with controllerErrorMsg in the reason"):
    val faulted = HcdState(state = HcdStateEnum.Faulted, controllerErrorMsg = "123 TCP lost sync")
    CommandGate.checkHcdState(faulted, "positionAxis", cswFaultedExempt) shouldBe
      Some("HCD Faulted: 123 TCP lost sync")

  test("checkHcdState: Faulted with empty controllerErrorMsg falls back to generic reason"):
    val faulted = HcdState(state = HcdStateEnum.Faulted, controllerErrorMsg = "")
    CommandGate.checkHcdState(faulted, "positionAxis", cswFaultedExempt) shouldBe
      Some("HCD Faulted: HCD is Faulted")

  test("checkHcdState: Faulted exempts faultReset on both paths"):
    val faulted = HcdState(state = HcdStateEnum.Faulted, controllerErrorMsg = "x")
    CommandGate.checkHcdState(faulted, "faultReset", cswFaultedExempt) shouldBe None
    CommandGate.checkHcdState(faulted, "faultReset", hmiFaultedExempt) shouldBe None

  test("checkHcdState: Faulted exempts setSoftLimits on the HMI path but NOT the CSW path"):
    val faulted = HcdState(state = HcdStateEnum.Faulted, controllerErrorMsg = "x")
    // HMI exempts it (HMI-internal flag flip, useful before recovery)
    CommandGate.checkHcdState(faulted, "setSoftLimits", hmiFaultedExempt) shouldBe None
    // CSW does not have setSoftLimits at all, so it is gated like any other command
    CommandGate.checkHcdState(faulted, "setSoftLimits", cswFaultedExempt) shouldBe
      Some("HCD Faulted: x")

  // ========================================
  // checkAxisState — state-machine gate (SDD Figure 4-2)
  // ========================================
  // Thin pass-through to AxisStateEnum.validateCommand (exercised exhaustively
  // in AxisStateValidationTest); these cases verify the wiring only.

  test("checkAxisState: permitted transition returns None"):
    val idle = AxisState(axisState = AxisStateEnum.Idle)
    CommandGate.checkAxisState(idle, "positionAxis") shouldBe None

  test("checkAxisState: invalid transition returns the enum's reason"):
    val homing = AxisState(axisState = AxisStateEnum.Homing)
    CommandGate.checkAxisState(homing, "positionAxis") shouldBe
      Some("positionAxis command not valid in Homing state")

  test("checkAxisState: stopAxis is permitted from any state"):
    for s <- AxisStateEnum.values do
      CommandGate.checkAxisState(AxisState(axisState = s), "stopAxis") shouldBe None

  // ========================================
  // checkSoftLimit — envelope gate for positionAxis / offsetAxis
  // ========================================

  // Linear axis, limits [0, 100], enabled, at position 0 unless overridden.
  private def linearAxis(position: Double = 0.0): AxisState =
    AxisState(
      axisState        = AxisStateEnum.Idle,
      position         = position,
      mechanismType    = MechanismType.Linear,
      lowerLimit       = Some(0.0),
      upperLimit       = Some(100.0),
      softLimitsEnabled = true
    )

  test("checkSoftLimit: positionAxis within the envelope is accepted"):
    CommandGate.checkSoftLimit(linearAxis(), "positionAxis", "A", Some(50.0)) shouldBe None

  test("checkSoftLimit: positionAxis above the upper limit is rejected with a path-identical message"):
    CommandGate.checkSoftLimit(linearAxis(), "positionAxis", "A", Some(150.0)) shouldBe
      Some("positionAxis A rejected: target 150 exceeds upper soft limit 100")

  test("checkSoftLimit: positionAxis below the lower limit is rejected"):
    CommandGate.checkSoftLimit(linearAxis(), "positionAxis", "A", Some(-5.0)) shouldBe
      Some("positionAxis A rejected: target -5 below lower soft limit 0")

  test("checkSoftLimit: offsetAxis resolves target as position + distance (out of range)"):
    // position 90 + distance 20 = 110 > upper 100
    CommandGate.checkSoftLimit(linearAxis(position = 90.0), "offsetAxis", "B", Some(20.0)) shouldBe
      Some("offsetAxis B rejected: target 110 exceeds upper soft limit 100")

  test("checkSoftLimit: offsetAxis resolves target as position + distance (in range)"):
    // position 10 + distance 20 = 30, within [0, 100]
    CommandGate.checkSoftLimit(linearAxis(position = 10.0), "offsetAxis", "B", Some(20.0)) shouldBe None

  test("checkSoftLimit: commands without a target are a no-op"):
    CommandGate.checkSoftLimit(linearAxis(), "homeAxis", "A", None) shouldBe None
    CommandGate.checkSoftLimit(linearAxis(), "stopAxis", "A", None) shouldBe None

  test("checkSoftLimit: positionAxis with no raw target supplied is a no-op"):
    CommandGate.checkSoftLimit(linearAxis(), "positionAxis", "A", None) shouldBe None

  test("checkSoftLimit: rotating axis is exempt (no soft limits)"):
    val rotating = AxisState(
      axisState     = AxisStateEnum.Idle,
      mechanismType = MechanismType.Rotating,
      lowerLimit    = Some(0.0),
      upperLimit    = Some(100.0)
    )
    CommandGate.checkSoftLimit(rotating, "positionAxis", "A", Some(150.0)) shouldBe None

  test("checkSoftLimit: softLimitsEnabled=false bypasses enforcement"):
    val bypassed = linearAxis().copy(softLimitsEnabled = false)
    CommandGate.checkSoftLimit(bypassed, "positionAxis", "A", Some(150.0)) shouldBe None

  test("checkSoftLimit: unconfigured limits (None) disable enforcement"):
    val unconfigured = AxisState(
      axisState        = AxisStateEnum.Idle,
      mechanismType    = MechanismType.Linear,
      lowerLimit       = None,
      upperLimit       = None,
      softLimitsEnabled = true
    )
    CommandGate.checkSoftLimit(unconfigured, "positionAxis", "A", Some(150.0)) shouldBe None