package csw.proto.galil.client

import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import org.apache.pekko.util.Timeout
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.Assembly
import csw.location.api.models.Connection.PekkoConnection
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandName, Setup}
import csw.params.core.generics.KeyType._
import csw.params.core.models.{Choice, Units}
import csw.prefix.models.{Prefix, Subsystem}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * AssemblyLoadApp — a direct-CommandService load generator that drives the
 * APS-ICS assemblies to real, distinct targets ALL AT ONCE, to exercise the
 * GalilMotion HCDs under the simultaneous, multi-axis, multi-assembly load the
 * sequencer must be able to produce (and that surfaced the S82 stuck-Homing
 * thread-reservation race).
 *
 * WHAT "LOAD" MEANS HERE
 * ----------------------
 * The realistic stimulus is not re-homing — it is commanding every assembly to
 * a DIFFERENT operational target simultaneously, so they run for different
 * durations and finish at staggered times, then commanding the whole set to a
 * DIFFERENT target, and so on.  That is what a "target set" is below.  The
 * sequencer will not normally move everything at once; this checks that the
 * system tolerates it when it does.  (Simulators complete faster than real
 * controllers, so target distances are varied to spread completion times.)
 *
 * It drives assemblies the way the sequencer does — resolve from the Location
 * Service, submit `Setup`s via `CommandService` — NOT through the ESW gateway
 * (that is the browser/UI path).  So the HCD / assembly / controller
 * concurrency is the only system under test.  Modelled on `TrackInjectorApp`.
 *
 * REAL PER-ASSEMBLY TARGETS (from the generated *Keys and the SDD configs)
 * -----------------------------------------------------------------------
 *   focus stages (PSH/PIT/APT)    positionFocusStage  ABSOLUTE value mm   (±100)
 *   InsertionStage                positionStage       ABSOLUTE value mm   (±100)
 *   filter wheels (PSH/PIT)       selectFilter        filter (7 named)
 *   filter wheel  (APT)           selectFilter        filter (ND1/ND2/NB589/OPEN)
 *   pupil-mask wheels (PSH/PIT)   selectPupilMask     pupilMask (named)
 *   K-Mirror                      positionKMirror     ABSOLUTE positionValue deg (MANUAL)
 *   TiltPlate                     positionTiltPlate   ABSOLUTE xValue/yValue mm
 *   SteeringBeamSplitter          positionBeamSplitter ABSOLUTE xValue/yValue mm
 *   CollimatorUnit                positionFrontAxis / positionRearAxis (alternated)
 *   CalibrationSourceStage        setSlot             slotNumber (1..5)
 *   FiberSourceStage              positionSource      ABSOLUTE X/Y/Z mm      (3 axes)
 *   PupilMaskStage                positionMaskStage   ABSOLUTE X/Y mm, Phi deg (3 axes)
 * The multi-axis commands (positionTiltPlate, positionSource, positionMaskStage,
 * positionBeamSplitter) move all of an assembly's axes in ONE command — that is
 * the within-assembly simultaneous-axis case (controllers 3/4).  Keys are built
 * locally by name+type (the WheelAssemblyHandlers pattern), so this needs no
 * dependency on ics-assemblies.
 *
 * VALIDATION GATES that shape the design (MotionAssemblyHandlers.validateCommand)
 * ------------------------------------------------------------------------------
 *   - PROCESSING: every command except `stop` is rejected.  => cannot pipeline a
 *     single assembly; contention comes from firing WIDTH-WISE across the set.
 *     A target set is therefore one command per assembly, awaited as a barrier
 *     before the next set (so no assembly is over-driven).
 *   - PRE_HOMED: only `configure`/`home` accepted.  => a configure+home warm-up
 *     precedes any target set.
 *   - `stop` penetrates the PROCESSING gate (S79 SDD 6.1.3.3.2 exemption).
 *
 * CONTENTION GRADIENT (IcsAssembliesContainer.conf)
 * -------------------------------------------------
 *   ctrl 1: 6 co-resident single-axis assemblies  (max cross-assembly grab)
 *   ctrl 4: 2 assemblies × 3 axes                  (max within-assembly fan-out)
 *   ctrl 2: 4 single-axis   ctrl 3: 4 incl. TiltPlate(2) + KMirror
 * `--controllers 1` is the six-way cross-assembly primitive; `--controllers 4`
 * the multi-axis primitive.
 *
 * TIME: none of these commands carries a TAI/validTime param, so the PVT
 * TAI-vs-UTC hazard (S78/S82) does not apply.  Wall clock is used only for
 * latency reporting.
 *
 * SCENARIOS (--scenario)
 * ----------------------
 *   list          : resolve + print the selected set with each assembly's move
 *                   command and target count; exit.
 *   configure-home: barrier `configure` wave then barrier `home` wave (warm-up).
 *   targets       : warm up, then cycle target sets until --duration — each set
 *                   sends every assembly to its next distinct target, barrier per
 *                   set.  The workhorse.  --overdrive removes the barrier and
 *                   paces at --cadence-hz (deliberately over-drives the
 *                   PROCESSING gate; expect gate Invalids, tallied separately).
 *   stop-storm    : fire a target set (no barrier), wait --stop-delay-ms, then a
 *                   barrier `stop` wave; repeat.  Stop under active motion.
 *   stop-idle     : warm up (axes settle at home/idle), then repeated barrier
 *                   `stop` waves on IDLE axes.  The S82 regression: on the STB
 *                   (or a sim whose #StopX finishes sub-scan) a stop on an idle
 *                   axis completes within one QR scan — the exact regime that
 *                   clobbered the thread→axis registry.  Expect the "registered
 *                   threads" polling reason and NO "INVARIANT VIOLATION".
 *
 * PASS/FAIL is judged from the run report (Completed vs gate-Invalid vs suspect,
 * plus latency) AND an HCD-log scrape — see ASSEMBLY_LOAD_TESTING.md.
 *
 * PREREQUISITES: csw-services, the GalilMotion HCDs, and IcsAssembliesContainer
 * all running and registered (services -> HCDs -> assemblies order).
 *
 * CLI: --scenario --controllers --assemblies --duration --cadence-hz
 *      --stop-delay-ms --stagger-ms --overdrive --skip-warmup --cmd-timeout
 *      --report --resolve-timeout  (run with --help).
 */
object AssemblyLoadApp {

  // ---------------------------------------------------------------------------
  // Topology — the 16 motion assemblies (IcsAssembliesContainer.conf).  Detector
  // assemblies take no motion commands and are excluded.  `axes` is the count a
  // single multi-axis move command drives.
  // ---------------------------------------------------------------------------
  private final case class AssemblyDef(componentName: String, controller: Int, axes: Int, kind: String)

  private val Topology: Vector[AssemblyDef] = Vector(
    AssemblyDef("ICS.PSH.FocusStage",                1, 1, "focusStage"),
    AssemblyDef("ICS.PSH.FilterWheel",               1, 1, "filterWheel"),
    AssemblyDef("ICS.PSH.PupilMaskWheel",            1, 1, "pupilWheel"),
    AssemblyDef("ICS.PIT.FocusStage",                1, 1, "focusStage"),
    AssemblyDef("ICS.PIT.FilterWheel",               1, 1, "filterWheel"),
    AssemblyDef("ICS.PIT.PupilMaskWheel",            1, 1, "pupilWheel"),
    AssemblyDef("ICS.STIM.InsertionStage",           2, 1, "insertionStage"),
    AssemblyDef("ICS.FOC.SteeringBeamSplitterStage", 2, 2, "beamSplitter"),
    AssemblyDef("ICS.FOC.CollimatorUnit",            2, 2, "collimator"),
    AssemblyDef("ICS.FOC.CalibrationSourceStage",    2, 1, "calibration"),
    AssemblyDef("ICS.APT.FocusStage",                3, 1, "focusStage"),
    AssemblyDef("ICS.APT.FilterWheel",               3, 1, "filterWheelApt"),
    AssemblyDef("ICS.FOC.TiltPlate",                 3, 2, "tiltPlate"),
    AssemblyDef("ICS.FOC.KMirror",                   3, 1, "kMirror"),
    AssemblyDef("ICS.STIM.FiberSourceStage",         4, 3, "fiberSource"),
    AssemblyDef("ICS.STIM.PupilMaskStage",           4, 3, "pupilMaskStage")
  )

  // Universal (common) command names.
  private val Configure = "configure"
  private val Home      = "home"
  private val Stop      = "stop"

  private val source = Prefix(Subsystem.CSW, "AssemblyLoadTest")

  // Target value pools.  Linear axes are ±100 mm (all confs), default 0, so these
  // absolute targets are always in range; rotary targets are in degrees.
  private val StageMm: Vector[Float]                    = Vector(-50f, 50f, -80f, 30f)
  private val XY: Vector[(Float, Float)]                = Vector((-50f, 50f), (60f, -60f), (-80f, -30f), (20f, 80f))
  // TiltPlate maps xValue/yValue through a 0.5 stage->M1 factor, so effective
  // stage travel is 2x the commanded value; keep |value| <= 40 to stay inside the
  // ±100 mm stage limits (M1 50 -> stage 100 is the boundary that rejected ±60/±80).
  private val TiltXY: Vector[(Float, Float)]            = Vector((-40f, 30f), (40f, -30f), (-30f, -40f), (20f, 40f))
  private val XYZ: Vector[(Float, Float, Float)]        = Vector((-50f, 50f, -30f), (60f, -40f, 70f), (-80f, 20f, -60f), (10f, -70f, 40f))
  private val XYPhi: Vector[(Float, Float, Float)]      = Vector((-50f, 50f, 45f), (60f, -40f, -45f), (-80f, 20f, 120f), (10f, -70f, -90f))
  private val KMirrorDeg: Vector[Float]                 = Vector(45f, -45f, 120f, -90f)

  // ---------------------------------------------------------------------------
  // Per-assembly target builders.  Each returns the ordered list of Setups the
  // assembly cycles through; keys are built locally by name+type (matched to the
  // generated *Keys), the same technique WheelAssemblyHandlers uses to read wheel
  // params without a generated-Keys dependency.
  // ---------------------------------------------------------------------------
  private def targetsFor(name: String): Vector[Setup] = {
    val method  = ChoiceKey.make("positioningMethod", "ABSOLUTE", "RELATIVE")
    val abs     = method.set(Choice("ABSOLUTE"))

    def stageCmd(cmd: String, valueName: String, unit: Units): Vector[Setup] =
      val vk = FloatKey.make(valueName, unit)
      StageMm.map(v => Setup(source, CommandName(cmd), None).add(abs).add(vk.set(v)))

    def xyCmd(cmd: String, pool: Vector[(Float, Float)]): Vector[Setup] =
      val xk = FloatKey.make("xValue", Units.millimeter)
      val yk = FloatKey.make("yValue", Units.millimeter)
      pool.map { case (x, y) => Setup(source, CommandName(cmd), None).add(abs).add(xk.set(x)).add(yk.set(y)) }

    def selectCmd(cmd: String, keyName: String, choices: Vector[String]): Vector[Setup] =
      // One key per choice (each declaring just its own Choice). ChoiceKey.make's
      // varargs are Choice*, and splatting a Vector[String] does NOT apply the
      // per-element String->Choice conversion that string literals get — so build
      // the key with an explicit Choice. The wire param is (keyName, ChoiceKey,
      // Choice(c)); the assembly reads it by name+type, independent of the key's
      // declared choice set.
      choices.map { c =>
        val k = ChoiceKey.make(keyName, Choice(c))
        Setup(source, CommandName(cmd), None).add(k.set(Choice(c)))
      }

    name match
      case "ICS.PSH.FocusStage" | "ICS.PIT.FocusStage" | "ICS.APT.FocusStage" =>
        stageCmd("positionFocusStage", "value", Units.millimeter)

      case "ICS.STIM.InsertionStage" =>
        stageCmd("positionStage", "value", Units.millimeter)

      case "ICS.PSH.FilterWheel" | "ICS.PIT.FilterWheel" =>
        selectCmd("selectFilter", "filter",
          Vector("F890N", "F891N", "F850M", "F750W", "F810N", "F630N", "F865N"))

      case "ICS.APT.FilterWheel" =>
        selectCmd("selectFilter", "filter", Vector("ND1", "ND2", "NB589", "OPEN"))

      case "ICS.PSH.PupilMaskWheel" =>
        selectCmd("selectPupilMask", "pupilMask", Vector("PH-2-0", "SH-0", "SH-2", "SH-5", "Clear"))

      case "ICS.PIT.PupilMaskWheel" =>
        selectCmd("selectPupilMask", "pupilMask", Vector("PH-1-1", "Clear"))

      case "ICS.FOC.KMirror" =>
        val vk = FloatKey.make("positionValue", Units.degree)
        KMirrorDeg.map(d => Setup(source, CommandName("positionKMirror"), None).add(abs).add(vk.set(d)))

      case "ICS.FOC.TiltPlate"                 => xyCmd("positionTiltPlate", TiltXY)
      case "ICS.FOC.SteeringBeamSplitterStage" => xyCmd("positionBeamSplitter", XY)

      case "ICS.FOC.CollimatorUnit" =>
        // Two independent single-axis commands; alternate front/rear across the
        // cycle so both axes are exercised (they can't move in one command).
        val vk = FloatKey.make("positionValue", Units.millimeter)
        StageMm.zipWithIndex.map { case (v, i) =>
          val cmd = if i % 2 == 0 then "positionFrontAxis" else "positionRearAxis"
          Setup(source, CommandName(cmd), None).add(abs).add(vk.set(v))
        }

      case "ICS.FOC.CalibrationSourceStage" =>
        selectCmd("setSlot", "slotNumber", Vector("1", "2", "3", "4", "5"))

      case "ICS.STIM.FiberSourceStage" =>
        val xk = FloatKey.make("positionValueX", Units.millimeter)
        val yk = FloatKey.make("positionValueY", Units.millimeter)
        val zk = FloatKey.make("positionValueZ", Units.millimeter)
        XYZ.map { case (x, y, z) =>
          Setup(source, CommandName("positionSource"), None).add(abs).add(xk.set(x)).add(yk.set(y)).add(zk.set(z))
        }

      case "ICS.STIM.PupilMaskStage" =>
        val xk = FloatKey.make("positionValueX", Units.millimeter)
        val yk = FloatKey.make("positionValueY", Units.millimeter)
        val pk = FloatKey.make("positionValuePhi", Units.degree)
        XYPhi.map { case (x, y, p) =>
          Setup(source, CommandName("positionMaskStage"), None).add(abs).add(xk.set(x)).add(yk.set(y)).add(pk.set(p))
        }

      case _ => Vector.empty
  }

  // ---------------------------------------------------------------------------
  // Config / CLI
  // ---------------------------------------------------------------------------
  private final case class Config(
      scenario: String                   = "list",
      controllers: Set[Int]              = Set(1, 2, 3, 4),
      explicitAssemblies: Vector[String] = Vector.empty,
      durationSec: Double                = 60.0,
      cadenceHz: Double                  = 0.0,
      stopDelayMs: Long                  = 500L,
      staggerMs: Long                    = 0L,
      overdrive: Boolean                 = false,
      skipWarmup: Boolean                = false,
      cmdTimeoutSec: Double              = 120.0,
      reportPath: Option[String]         = None,
      resolveTimeoutSec: Double          = 30.0
  )

  private def parseArgs(args: Array[String]): Config = {
    var cfg = Config()
    var i   = 0
    while i < args.length do
      val flag = args(i)
      def next: String =
        if i + 1 >= args.length then sys.error(s"Missing value for $flag") else { i += 1; args(i) }
      flag match
        case "--scenario"        => cfg = cfg.copy(scenario = next.toLowerCase)
        case "--controllers"     =>
          val v = next
          cfg = if v.equalsIgnoreCase("all") then cfg.copy(controllers = Set(1, 2, 3, 4))
                else cfg.copy(controllers = v.split(",").map(_.trim.toInt).toSet)
        case "--assemblies"      => cfg = cfg.copy(explicitAssemblies = next.split(",").map(_.trim).toVector)
        case "--duration"        => cfg = cfg.copy(durationSec = next.toDouble)
        case "--cadence-hz"      => cfg = cfg.copy(cadenceHz = next.toDouble)
        case "--stop-delay-ms"   => cfg = cfg.copy(stopDelayMs = next.toLong)
        case "--stagger-ms"      => cfg = cfg.copy(staggerMs = next.toLong)
        case "--overdrive"       => cfg = cfg.copy(overdrive = true)
        case "--skip-warmup"     => cfg = cfg.copy(skipWarmup = true)
        case "--cmd-timeout"     => cfg = cfg.copy(cmdTimeoutSec = next.toDouble)
        case "--report"          => cfg = cfg.copy(reportPath = Some(next))
        case "--resolve-timeout" => cfg = cfg.copy(resolveTimeoutSec = next.toDouble)
        case "--help" | "-h"     => printUsage(); sys.exit(0)
        case other               => sys.error(s"Unknown flag: $other (use --help)")
      i += 1
    validate(cfg)
    cfg
  }

  private def validate(cfg: Config): Unit = {
    val scenarios = Set("list", "configure-home", "targets", "stop-storm", "stop-idle")
    require(scenarios.contains(cfg.scenario), s"--scenario must be one of ${scenarios.mkString(", ")}")
    require(cfg.durationSec > 0, "--duration must be > 0")
    require(cfg.cadenceHz >= 0, "--cadence-hz must be >= 0")
    require(cfg.cmdTimeoutSec > 0, "--cmd-timeout must be > 0")
    val known = Topology.map(_.componentName).toSet
    cfg.explicitAssemblies.foreach(n =>
      require(known.contains(n), s"unknown assembly '$n'; known: ${known.toVector.sorted.mkString(", ")}"))
  }

  private def printUsage(): Unit = println(
    """AssemblyLoadApp — drive ICS assemblies to real targets, all at once, to load the HCDs.
      |
      |  --scenario <name>       list | configure-home | targets | stop-storm | stop-idle
      |  --controllers <ids>     comma list (e.g. 1,3) or `all` (default all)
      |  --assemblies <names>    explicit component names under APS (overrides --controllers)
      |  --duration <sec>        targets/stop-storm/stop-idle run length (default 60)
      |  --cadence-hz <Hz>       inter-set pacing; 0 = as fast as possible (default 0)
      |  --stop-delay-ms <ms>    stop-storm motion lead before the stop wave (default 500)
      |  --stagger-ms <ms>       per-submit delay within a wave; 0 = simultaneous (default 0)
      |  --overdrive             targets: drop the per-set barrier (over-drive the gate)
      |  --skip-warmup           assume the set is already configured + homed
      |  --cmd-timeout <sec>     per-command submitAndWait timeout (default 120)
      |  --report <path>         write per-submit CSV run report
      |  --resolve-timeout <sec> Location Service resolve budget per assembly (default 30)
      |
      |Examples:
      |  ... AssemblyLoadApp --scenario list --controllers 1
      |  ... AssemblyLoadApp --scenario targets --controllers 1 --duration 120 --report c1.csv
      |  ... AssemblyLoadApp --scenario targets --duration 300          # whole instrument at once
      |  ... AssemblyLoadApp --scenario stop-idle --controllers 1 --duration 60  # S82 regression
      |""".stripMargin)

  // ---------------------------------------------------------------------------
  // A resolved target: an assembly + its CommandService + its target pool.
  // ---------------------------------------------------------------------------
  private final case class Target(defn: AssemblyDef, cs: CommandService, pool: Vector[Setup]):
    def name: String    = defn.componentName
    def controller: Int = defn.controller

  // ---------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------
  private final case class Record(
      waveIdx: Long, tSubmitMs: Long, assembly: String, controller: Int,
      command: String, outcome: String, latencyMs: Long)

  private val records     = new ConcurrentLinkedQueue[Record]()
  private val waveCounter = new AtomicLong(0L)

  private def outcomeOf(r: SubmitResponse): String = r match
    case _: Completed => "Completed"
    case i: Invalid   => s"Invalid(${i.issue.getClass.getSimpleName.stripSuffix("$")})"
    case _: Error     => "Error"
    case _: Locked    => "Locked"
    case _: Cancelled => "Cancelled"
    case _: Started   => "Started" // submitAndWait resolves to a final response; Started is anomalous

  // ---------------------------------------------------------------------------
  // Actor system / services
  // ---------------------------------------------------------------------------
  implicit private val typedSystem: ActorSystem[SpawnProtocol.Command] =
    ActorSystem(SpawnProtocol(), "AssemblyLoadApp")
  implicit private lazy val ec: ExecutionContextExecutor = typedSystem.executionContext

  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val running         = new AtomicBoolean(true)

  def main(args: Array[String]): Unit = {
    val cfg  = parseArgs(args)
    val host = InetAddress.getLocalHost.getHostName
    LoggingSystemFactory.start("AssemblyLoadApp", "0.1", host, typedSystem)
    implicit val log: csw.logging.api.scaladsl.Logger = GenericLoggerFactory.getLogger

    implicit val cmdTimeout: Timeout = Timeout(cfg.cmdTimeoutSec.seconds)
    val waveTimeout: FiniteDuration  = (cfg.cmdTimeoutSec + 30.0).seconds

    val selected: Vector[AssemblyDef] =
      if cfg.explicitAssemblies.nonEmpty then
        cfg.explicitAssemblies.flatMap(n => Topology.find(_.componentName == n))
      else
        Topology.filter(d => cfg.controllers.contains(d.controller))
    require(selected.nonEmpty, "no assemblies selected — check --controllers / --assemblies")

    log.info(s"AssemblyLoadApp scenario=${cfg.scenario} targets=${selected.size} " +
             s"controllers=${selected.map(_.controller).distinct.sorted.mkString(",")}")

    // Resolve each assembly; missing ones are logged and skipped.
    val targets: Vector[Target] = selected.flatMap { d =>
      val connection = PekkoConnection(ComponentId(Prefix(Subsystem.APS, d.componentName), Assembly))
      Await.result(
        locationService.resolve(connection, cfg.resolveTimeoutSec.seconds).map {
          case Some(loc) => Some(Target(d, CommandServiceFactory.make(loc), targetsFor(d.componentName)))
          case None      => log.error(s"could not locate ${d.componentName}; skipping"); None
        },
        (cfg.resolveTimeoutSec + 5.0).seconds
      )
    }
    require(targets.nonEmpty, "none of the selected assemblies could be resolved — is the container running?")
    printTargets(targets)

    if cfg.scenario == "list" then { shutdown(); return }

    val hook = new Thread(() => {
      if running.getAndSet(false) then
        log.info("shutdown signal — stopping all targets")
        stopAll(targets, waveTimeout)
    })
    Runtime.getRuntime.addShutdownHook(hook)

    try
      cfg.scenario match
        case "configure-home" => warmup(targets, cfg, waveTimeout, force = true)
        case "targets"        => runTargets(targets, cfg, waveTimeout)
        case "stop-storm"     => runStopStorm(targets, cfg, waveTimeout)
        case "stop-idle"      => runStopIdle(targets, cfg, waveTimeout)
        case other            => sys.error(s"unreachable scenario $other")
    catch
      case ex: Throwable => log.error(s"run aborted: ${ex.getMessage}", ex = ex)
    finally
      running.set(false)
      Try(Runtime.getRuntime.removeShutdownHook(hook))
      report(cfg, log)
      shutdown()
  }

  // ---------------------------------------------------------------------------
  // Wave primitive — fan a per-assembly Setup assignment across the set.  Each
  // submitAndWait is launched back-to-back (simultaneous when stagger=0), records
  // its own outcome+latency, and never fails the returned Future so a barrier
  // always completes.
  // ---------------------------------------------------------------------------
  private def fanSetups(assign: Seq[(Target, Setup)], staggerMs: Long)
                       (using cmdTimeout: Timeout): Seq[Future[Unit]] = {
    val wave = waveCounter.getAndIncrement()
    assign.map { case (t, setup) =>
      if staggerMs > 0 then Thread.sleep(staggerMs)
      val cmd     = setup.commandName.name
      val tSubmit = System.currentTimeMillis()
      val t0      = System.nanoTime()
      t.cs.submitAndWait(setup).transform { tried =>
        val latMs = (System.nanoTime() - t0) / 1000000L
        val outcome = tried match
          case Success(r)  => outcomeOf(r)
          case Failure(ex) => s"Future-failed(${ex.getClass.getSimpleName})"
        records.add(Record(wave, tSubmit, t.name, t.controller, cmd, outcome, latMs))
        Success(())
      }
    }
  }

  private def fanBarrier(assign: Seq[(Target, Setup)], staggerMs: Long, waveTimeout: FiniteDuration, label: String)
                        (using cmdTimeout: Timeout, log: csw.logging.api.scaladsl.Logger): Unit = {
    log.info(s"wave: $label x${assign.size} (barrier)")
    Await.result(Future.sequence(fanSetups(assign, staggerMs)), waveTimeout)
  }

  /** Same common command to every target. */
  private def sameCommand(cmd: String, targets: Seq[Target]): Seq[(Target, Setup)] =
    targets.map(t => t -> Setup(source, CommandName(cmd), None))

  /** Target set k: each assembly to its (k mod poolSize)-th target; assemblies
   *  with no target pool are skipped (they still receive warmup/stop waves). */
  private def targetSet(k: Int, targets: Seq[Target]): Seq[(Target, Setup)] =
    targets.flatMap(t => if t.pool.isEmpty then None else Some(t -> t.pool(k % t.pool.size)))

  // ---------------------------------------------------------------------------
  // Scenarios
  // ---------------------------------------------------------------------------
  private def warmup(targets: Seq[Target], cfg: Config, waveTimeout: FiniteDuration, force: Boolean)
                    (using cmdTimeout: Timeout, log: csw.logging.api.scaladsl.Logger): Unit =
    if cfg.skipWarmup && !force then log.info("skipping configure+home warm-up (--skip-warmup)")
    else
      fanBarrier(sameCommand(Configure, targets), cfg.staggerMs, waveTimeout, Configure)
      fanBarrier(sameCommand(Home, targets), cfg.staggerMs, waveTimeout, Home)

  private def runTargets(targets: Seq[Target], cfg: Config, waveTimeout: FiniteDuration)
                        (using cmdTimeout: Timeout, log: csw.logging.api.scaladsl.Logger): Unit = {
    warmup(targets, cfg, waveTimeout, force = false)
    val startMs    = System.currentTimeMillis()
    val durationMs = (cfg.durationSec * 1000.0).toLong
    val periodMs   = if cfg.cadenceHz > 0 then (1000.0 / cfg.cadenceHz).toLong else 0L
    var k          = 0
    while running.get() && (System.currentTimeMillis() - startMs) < durationMs do
      val assign = targetSet(k, targets)
      if cfg.overdrive then
        log.info(s"target set $k x${assign.size} (no barrier)")
        val _ = fanSetups(assign, cfg.staggerMs)
      else
        fanBarrier(assign, cfg.staggerMs, waveTimeout, s"targetSet[$k]")
      if periodMs > 0 then Thread.sleep(periodMs)
      k += 1
    if cfg.overdrive then Thread.sleep(math.min(cfg.cmdTimeoutSec, 10.0).toLong * 1000L)
  }

  private def runStopStorm(targets: Seq[Target], cfg: Config, waveTimeout: FiniteDuration)
                          (using cmdTimeout: Timeout, log: csw.logging.api.scaladsl.Logger): Unit = {
    warmup(targets, cfg, waveTimeout, force = false)
    val startMs    = System.currentTimeMillis()
    val durationMs = (cfg.durationSec * 1000.0).toLong
    var k          = 0
    while running.get() && (System.currentTimeMillis() - startMs) < durationMs do
      log.info(s"target set $k x${targets.size} (no barrier) then stop after ${cfg.stopDelayMs}ms")
      val _ = fanSetups(targetSet(k, targets), cfg.staggerMs)
      Thread.sleep(cfg.stopDelayMs)
      fanBarrier(sameCommand(Stop, targets), cfg.staggerMs, waveTimeout, Stop)
      k += 1
  }

  private def runStopIdle(targets: Seq[Target], cfg: Config, waveTimeout: FiniteDuration)
                         (using cmdTimeout: Timeout, log: csw.logging.api.scaladsl.Logger): Unit = {
    warmup(targets, cfg, waveTimeout, force = false)
    val startMs    = System.currentTimeMillis()
    val durationMs = (cfg.durationSec * 1000.0).toLong
    val periodMs   = if cfg.cadenceHz > 0 then (1000.0 / cfg.cadenceHz).toLong else 0L
    while running.get() && (System.currentTimeMillis() - startMs) < durationMs do
      fanBarrier(sameCommand(Stop, targets), cfg.staggerMs, waveTimeout, s"$Stop(idle)")
      if periodMs > 0 then Thread.sleep(periodMs)
  }

  private def stopAll(targets: Seq[Target], waveTimeout: FiniteDuration)(using cmdTimeout: Timeout): Unit =
    Try(Await.result(Future.sequence(fanSetups(sameCommand(Stop, targets), 0L)), waveTimeout))

  // ---------------------------------------------------------------------------
  // Reporting
  // ---------------------------------------------------------------------------
  private def printTargets(targets: Seq[Target]): Unit = {
    println(s"\nResolved ${targets.size} assemblies:")
    targets.sortBy(t => (t.controller, t.name)).foreach { t =>
      val cmd = t.pool.headOption.map(_.commandName.name).getOrElse("<none>")
      println(f"  controller ${t.controller}  ${t.name}%-34s axes=${t.defn.axes}  move=$cmd%-20s targets=${t.pool.size}")
    }
    val byCtrl = targets.groupBy(_.controller).view.mapValues(_.size).toVector.sorted
    println("  per-controller fan-out width: " + byCtrl.map { case (c, n) => s"ctrl$c=$n" }.mkString(", "))
    println()
  }

  private def pct(sorted: IndexedSeq[Long], p: Double): Long =
    if sorted.isEmpty then 0L else sorted(math.min(sorted.length - 1, (p * sorted.length).toInt))

  private def report(cfg: Config, log: csw.logging.api.scaladsl.Logger): Unit = {
    val all = records.asScala.toVector
    if all.isEmpty then { log.info("no submits recorded"); return }

    cfg.reportPath.foreach { path =>
      val sb = new StringBuilder("wave,tSubmitMs,assembly,controller,command,outcome,latencyMs\n")
      all.sortBy(r => (r.waveIdx, r.assembly)).foreach { r =>
        sb.append(s"${r.waveIdx},${r.tSubmitMs},${r.assembly},${r.controller},${r.command},${r.outcome},${r.latencyMs}\n")
      }
      Try(java.nio.file.Files.writeString(java.nio.file.Paths.get(path), sb.toString)) match
        case Success(_)  => log.info(s"wrote CSV report: $path (${all.size} rows)")
        case Failure(ex) => log.error(s"could not write report $path: ${ex.getMessage}")
    }

    println("\n==================== AssemblyLoadApp run report ====================")
    println(s"total submits: ${all.size}")

    println("\noutcome tally by command:")
    all.groupBy(_.command).toVector.sortBy(_._1).foreach { case (cmd, rs) =>
      println(f"  $cmd%-24s (${rs.size} submits)")
      rs.groupBy(_.outcome).toVector.sortBy(-_._2.size).foreach { case (out, os) =>
        println(f"      $out%-30s ${os.size}%5d")
      }
    }

    val completed = all.count(_.outcome == "Completed")
    val invalid   = all.count(_.outcome.startsWith("Invalid"))
    val cancelled = all.count(_.outcome == "Cancelled")
    // Cancelled is EXPECTED under stop-storm (a stop interrupting an in-flight
    // move yields Cancelled) — its own category, not a finding. Suspect is the
    // real signal: Error / Future-failed / Locked / Started.
    val suspect   = all.filterNot(r =>
      r.outcome == "Completed" || r.outcome.startsWith("Invalid") || r.outcome == "Cancelled")
    println(s"\nheadline: Completed=$completed  Invalid(gate/validation)=$invalid  " +
            s"Cancelled(interrupted)=$cancelled  suspect=${suspect.size}")
    if suspect.nonEmpty then
      println("  SUSPECT outcomes (investigate — pair with the HCD-log scrape):")
      suspect.groupBy(r => (r.command, r.outcome)).toVector.sortBy(-_._2.size).foreach { case ((cmd, out), rs) =>
        val ex = rs.take(3).map(r => s"${r.assembly}@ctrl${r.controller}").mkString(", ")
        println(f"    $cmd%-22s $out%-26s ${rs.size}%4d   e.g. $ex")
      }
    else
      println("  no suspect outcomes (all Completed or gate-Invalid).")

    println("\ncompleted-command latency (ms) per command  [min  p50  p90  p99  max]:")
    all.filter(_.outcome == "Completed").groupBy(_.command).toVector.sortBy(_._1).foreach { case (cmd, rs) =>
      val s = rs.map(_.latencyMs).sorted.toIndexedSeq
      println(f"  $cmd%-24s ${s.head}%6d ${pct(s, 0.50)}%6d ${pct(s, 0.90)}%6d ${pct(s, 0.99)}%6d ${s.last}%6d")
    }
    println("====================================================================\n")
  }

  private def shutdown(): Unit = {
    typedSystem.terminate()
    Try(Await.result(typedSystem.whenTerminated, 10.seconds))
    System.exit(0)
  }
}
