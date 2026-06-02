package csw.proto.galil.hcd

/**
 * Shared, pure command-gating logic used by both command-entry paths:
 *
 *   - the CSW validate path  (GalilHcd.validateCommand), and
 *   - the HMI command path    (HmiServer.handleCommandRequest).
 *
 * Why this exists: the HMI bypasses the CSW command framework and dispatches
 * directly to CommandHandlerActor, so any gate enforced in
 * GalilHcd.validateCommand must be enforced again on the HMI path or HMI
 * commands escape it entirely.  Historically the two paths reimplemented the
 * gate separately and drifted — the faultReset-while-Faulted exemption was
 * easy to miss, and the HMI path never enforced the axis-state-machine check
 * at all (state-invalid HMI commands were caught only by the CHA execution
 * backstop, which reports via the CRM the HMI does not observe, so they
 * produced no synchronous rejection).  These functions are the single source
 * of truth for the gate *decisions*.  Each caller is responsible only for
 *   (1) querying the state (HcdState / AxisState) to pass in, and
 *   (2) wrapping the returned reason in its own response type
 *       (CSW Invalid(...) vs HMI JSON {"status":"Error", ...}).
 *
 * All functions are pure: no InternalState query, no logging, no side
 * effects — so they are directly unit-testable without an actor harness.
 *
 * The axis-state-machine decision itself lives on AxisStateEnum.validateCommand
 * (SDD Figure 4-2) and the per-axis envelope on AxisState.checkSoftLimit; the
 * CHA execution backstop (guardAxisState / handlePositionAxis / handleOffsetAxis)
 * already calls those canonical methods directly, so this object deliberately
 * does not reimplement them — checkAxisState is a thin pass-through and
 * checkSoftLimit only adds the target-resolution and message-formatting glue
 * that the two validate paths previously duplicated.
 */
object CommandGate:

  /**
   * HCD-lifecycle gate.  Returns None if the command may proceed in the given
   * HCD state, or Some(reason) describing why it is rejected.
   *
   *   Uninitialized — every command rejected, no exemptions (faultReset has
   *                   nothing to reset; other commands would race the init
   *                   sequence).
   *   Faulted       — every command rejected except those in `faultedExempt`.
   *   Ready         — proceed.
   *
   * The CSW path passes `faultedExempt = Set("faultReset")`.  The HMI path
   * passes `Set("faultReset", "setSoftLimits")` because setSoftLimits is an
   * HMI-internal flag flip with no controller I/O that is useful while
   * preparing limit-switch tests before a faultReset.  Uninitialized is never
   * exempt on either path.
   *
   * Reason strings match what each path produced previously, so the same
   * command rejected on either path reports an identical message.
   */
  def checkHcdState(
    hcdState: HcdState,
    commandName: String,
    faultedExempt: Set[String]
  ): Option[String] =
    hcdState.state match
      case HcdStateEnum.Uninitialized =>
        Some("HCD Uninitialized — commands not yet accepted")
      case HcdStateEnum.Faulted if !faultedExempt.contains(commandName) =>
        val reason =
          if hcdState.controllerErrorMsg.nonEmpty then hcdState.controllerErrorMsg
          else "HCD is Faulted"
        Some(s"HCD Faulted: $reason")
      case _ =>
        None

  /**
   * Axis-state-machine gate (SDD Figure 4-2).  Thin pass-through to the
   * canonical AxisStateEnum.validateCommand so the two validate paths and the
   * CHA execution backstop all share one definition.  Returns None if the
   * command is permitted in the axis's current state, Some(reason) otherwise.
   */
  def checkAxisState(axisState: AxisState, commandName: String): Option[String] =
    axisState.axisState.validateCommand(commandName)

  /**
   * Soft-limit gate for the two commands that carry a target.  `rawTarget` is
   * the command's target value as supplied by the client: absolute encoder
   * counts for positionAxis, a relative distance for offsetAxis.  For every
   * other command (and when no target is supplied) this is a no-op returning
   * None.
   *
   * The absolute target evaluated against the envelope is the raw value for
   * positionAxis and (current position + raw distance) for offsetAxis — the
   * same accumulated-count `position` field used elsewhere.  Enforcement is
   * further gated inside AxisState.checkSoftLimit (linear axes only,
   * softLimitsEnabled true, limits configured), so this returns None whenever
   * limits do not apply.
   *
   * `axisLabel` is used only to format the rejection message identically
   * across paths (e.g. "A"); AxisState does not carry its own axis identity.
   */
  def checkSoftLimit(
    axisState: AxisState,
    commandName: String,
    axisLabel: String,
    rawTarget: Option[Double]
  ): Option[String] =
    val absTarget: Option[Double] = (commandName, rawTarget) match
      case ("positionAxis", Some(t)) => Some(t)
      case ("offsetAxis",   Some(t)) => Some(axisState.position + t)
      case _                         => None
    absTarget
      .flatMap(axisState.checkSoftLimit)
      .map(reason => s"$commandName $axisLabel rejected: $reason")