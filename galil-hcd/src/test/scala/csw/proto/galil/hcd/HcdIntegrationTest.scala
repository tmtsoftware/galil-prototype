package csw.proto.galil.hcd

import com.typesafe.config.ConfigFactory
import csw.command.client.CommandServiceFactory
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.params.commands.{CommandName, Setup}
import csw.params.commands.CommandResponse.*
import csw.params.core.models.Choice
import csw.params.core.states.{CurrentState, StateName}
import csw.prefix.models.{Prefix, Subsystem}
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import csw.proto.galil.GalilMotionKeys
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.BeforeAndAfterEach
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe

/**
 * Integration tests for long-running commands with real Galil controller hardware.
 *
 * These tests exercise the full actor chain:
 *   CommandService → GalilHcd.onSubmit → CommandHandlerActor → ControllerInterfaceActor
 *   StatusMonitor → InternalStateActor → CommandWatcherActor → CRM
 *   CurrentStatePublisherActor publishes state transitions
 *
 * Tests verify:
 * 1. Commands are accepted and return Started
 * 2. CurrentStateAxis shows state transitions (Moving, Homing, etc.)
 * 3. CommandStateAxis shows command execution state (activeThread, moving)
 * 4. Commands complete (queryFinal returns Completed)
 * 5. Final state is consistent (Idle, inPosition, not moving)
 *
 * MODES:
 * - Simulator: sbt -Dgalil.config.path=GalilHcdConfig-Simulator.conf "galil-hcd/testOnly *HcdIntegrationTest"
 *   Requires GalilSimulatorApp running on 127.0.0.1:8888
 * - Hardware:  sbt "galil-hcd/testOnly *HcdIntegrationTest"
 *   Requires Galil DMC-500x0 controller at configured address with protoHCD_lab.dmc loaded
 *
 * PREREQUISITES:
 * - CLUSTER_SEEDS environment variable UNSET
 * - No CSW services running (FrameworkTestKit starts its own)
 * - For hardware: motors safe to move (no mechanical obstructions)
 *
 * Run: sbt "galil-hcd/testOnly *HcdIntegrationTest"
 */
class HcdIntegrationTest
  extends ScalaTestFrameworkTestKit(AlarmServer, EventServer)
  with AnyFunSuiteLike
  with BeforeAndAfterEach:

  import frameworkTestKit.*
  import GalilMotionKeys.`ICS.HCD.GalilMotion`.*

  val hcdPrefix = Prefix(Subsystem.APS, "ICS.HCD.GalilMotion")
  
  // Timeouts
  val resolveTimeout = 15.seconds   // HCD startup + controller connection
  val commandTimeout = 30.seconds   // motor motion completion
  val stateTimeout   = 10.seconds   // waiting for CurrentState publication

  override def beforeAll(): Unit =
    println("=== HcdIntegrationTest: Starting FrameworkTestKit ===")
    try {
      // Use externally-provided config if set (e.g. -Dgalil.config.path=GalilHcdConfig-Simulator.conf)
      // Default to hardware config when no external override is provided.
      val configPath = Option(System.getProperty("galil.config.path")).getOrElse {
        System.setProperty("galil.config.path", "GalilHcdConfig-Hardware.conf")
        "GalilHcdConfig-Hardware.conf"
      }
      ConfigFactory.invalidateCaches()
      println(s"=== galil.config.path: $configPath ===")
      
      // Verify config can be loaded from classpath
      try {
        val configName = configPath.stripSuffix(".conf")
        val testConfig = ConfigFactory.load(configName)
        println(s"=== Config loaded: controller.host = ${testConfig.getIntList("controller.host")} ===")
        println(s"=== Config loaded: controller.port = ${testConfig.getInt("controller.port")} ===")
        println(s"=== Config loaded: simulate = ${testConfig.getBoolean("simulate")} ===")
      } catch {
        case e: Exception =>
          println(s"=== WARNING: Could not pre-load config: ${e.getMessage} ===")
      }
      super.beforeAll()
      println("=== FrameworkTestKit services started ===")

      // Spawn the HCD — it will connect to hardware or simulator per config
      spawnStandalone(ConfigFactory.load("GalilHcdStandalone.conf"))
      println("=== HCD spawned -- waiting for controller connection ===")

      // Wait for HCD to register with Location Service
      val connection = PekkoConnection(ComponentId(hcdPrefix, ComponentType.HCD))
      println(s"=== Waiting for HCD registration: $connection ===")
      val locationOpt = Await.result(
        locationService.resolve(connection, resolveTimeout),
        resolveTimeout + 2.seconds
      )
      locationOpt match {
        case Some(loc) =>
          println(s"=== HCD registered successfully: ${loc.connection} ===")
        case None =>
          val msg = "HCD did not register with Location Service. Check: controller reachable? Config loaded? CLUSTER_SEEDS unset?"
          println(s"=== FATAL: $msg ===")
          throw new RuntimeException(msg)
      }
    } catch {
      case e: Exception =>
        println(s"=== ERROR in beforeAll: ${e.getMessage} ===")
        e.printStackTrace()
        throw e
    }

  override def afterAll(): Unit =
    println("=== HcdIntegrationTest: Shutting down ===")
    try {
      super.afterAll()
      System.clearProperty("galil.config.path")
      println("=== Shutdown complete ===")
    } catch {
      case e: Exception =>
        println(s"=== ERROR in afterAll: ${e.getMessage} ===")
        e.printStackTrace()
    }

  /** Resolve HCD and create CommandService */
  private def getCommandService = {
    val connection = PekkoConnection(ComponentId(hcdPrefix, ComponentType.HCD))
    val pekkoLocation = Await.result(
      locationService.resolve(connection, resolveTimeout),
      resolveTimeout
    ).get
    CommandServiceFactory.make(pekkoLocation)
  }
  
  /**
   * Home an axis and wait for completion.
   */
  private def homeAxisAndWait(commandService: csw.command.api.scaladsl.CommandService, axisName: String): Unit = {
    val setup = makeSetup("homeAxis", "axis" -> axisName)
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    submitResponse match {
      case s: Started =>
        Await.result(
          commandService.queryFinal(s.runId)(Timeout(commandTimeout)),
          commandTimeout
        )
      case _: Completed => // immediate completion
      case other =>
        println(s"  WARNING: homeAxis $axisName returned unexpected: $other")
    }
  }

  /**
   * Set axis speed via configAxis and wait for completion.
   * Used by interruption tests to slow the axis so a short move takes long enough to interrupt.
   */
  private def setAxisSpeed(commandService: csw.command.api.scaladsl.CommandService, axisName: String, speed: Float): Unit = {
    var setup = Setup(hcdPrefix, CommandName("configAxis"), None)
    setup = setup.add(ConfigAxisCommand.axisKey.set(Choice(axisName)))
    setup = setup.add(ConfigAxisCommand.velocityKey.set(speed))
    val submit = Await.result(commandService.submit(setup), 5.seconds)
    submit match {
      case s: Started =>
        Await.result(commandService.queryFinal(s.runId)(Timeout(5.seconds)), 5.seconds)
      case _: Completed =>
      case other =>
        println(s"  WARNING: configAxis $axisName speed=$speed returned: $other")
    }
  }

  /** Build a Setup command with the HCD prefix */
  private def makeSetup(commandName: String, params: (String, Any)*): Setup = {
    var setup = Setup(hcdPrefix, CommandName(commandName), None)
    params.foreach { case (key, value) =>
      value match {
        case s: String if key == "axis" =>
          // Use the ICD axis key for this command type
          val axisKey = commandName match {
            case "positionAxis" => PositionAxisCommand.axisKey
            case "homeAxis"     => HomeAxisCommand.axisKey
            case "stopAxis"     => StopAxisCommand.axisKey
            case "offsetAxis"   => OffsetAxisCommand.axisKey
            case "configAxis"   => ConfigAxisCommand.axisKey
            case "trackAxis"    => TrackAxisCommand.axisKey
            case _              => PositionAxisCommand.axisKey
          }
          setup = setup.add(axisKey.set(Choice(s)))
        case f: Float if key == "target" =>
          setup = setup.add(PositionAxisCommand.targetKey.set(f))
        case f: Float if key == "distance" =>
          setup = setup.add(OffsetAxisCommand.distanceKey.set(f))
        case d: Double if key == "target" =>
          setup = setup.add(PositionAxisCommand.targetKey.set(d.toFloat))
        case d: Double if key == "distance" =>
          setup = setup.add(OffsetAxisCommand.distanceKey.set(d.toFloat))
        case f: Float if key == "target1" =>
          setup = setup.add(TrackAxisCommand.target1Key.set(f))
        case d: Double if key == "target1" =>
          setup = setup.add(TrackAxisCommand.target1Key.set(d.toFloat))
        case f: Float if key == "target2" =>
          setup = setup.add(TrackAxisCommand.target2Key.set(f))
        case d: Double if key == "target2" =>
          setup = setup.add(TrackAxisCommand.target2Key.set(d.toFloat))
        case _ =>
          // Ignore unknown params
      }
    }
    setup
  }

  /**
   * Collect CurrentState events matching a StateName until a condition is met or timeout.
   * Returns all collected states.
   */
  private def collectStatesUntil(
    commandService: csw.command.api.scaladsl.CommandService,
    stateName: StateName,
    condition: CurrentState => Boolean,
    timeout: FiniteDuration = stateTimeout
  ): Seq[CurrentState] = {
    val probe = TestProbe[CurrentState]()
    val collected = scala.collection.mutable.ArrayBuffer[CurrentState]()
    
    val subscription = commandService.subscribeCurrentState(
      Set(stateName),
      currentState => probe.ref ! currentState
    )
    
    val deadline = timeout.fromNow
    var done = false
    
    while (!done && deadline.hasTimeLeft()) {
      try {
        val state = probe.receiveMessage(1.second)
        collected += state
        if (condition(state)) done = true
      } catch {
        case _: AssertionError => // TestProbe timeout -- continue
      }
    }
    
    subscription.cancel()
    collected.toSeq
  }

  // ==========================================================================
  // Test 1: HCD resolves, is ready, and home both axes to establish known state
  // ==========================================================================
  
  test("HCD should be ready and home all axes") {
    val commandService = getCommandService
    
    // Verify HCD is running
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateCurrentState.eventKey.eventName.name)),
      cs => probe.ref ! cs
    )
    
    val systemState = probe.receiveMessage(stateTimeout)
    assert(systemState.exists(CurrentStateCurrentState.stateKey),
      "System CurrentState should contain stateKey")
    
    val state = systemState(CurrentStateCurrentState.stateKey).head
    println(s"  HCD state: ${state.name}")
    assert(state.name == "Idle" || state.name == "Ready",
      s"HCD should be Idle or Ready, was: ${state.name}")
    sub.cancel()
    
    // Home axes A and B in parallel to establish known positions
    val setupA = makeSetup("homeAxis", "axis" -> "A")
    val setupB = makeSetup("homeAxis", "axis" -> "B")
    
    val futA = commandService.submit(setupA)
    val futB = commandService.submit(setupB)
    
    val respA = Await.result(futA, 5.seconds)
    val respB = Await.result(futB, 5.seconds)
    println(s"  homeAxis A submit: $respA")
    println(s"  homeAxis B submit: $respB")
    
    assert(respA.isInstanceOf[Started], s"homeAxis A should start, got: $respA")
    assert(respB.isInstanceOf[Started], s"homeAxis B should start, got: $respB")
    
    // Wait for both to complete in parallel
    val finalA = commandService.queryFinal(respA.runId)(Timeout(commandTimeout))
    val finalB = commandService.queryFinal(respB.runId)(Timeout(commandTimeout))
    
    val resultA = Await.result(finalA, commandTimeout)
    val resultB = Await.result(finalB, commandTimeout)
    println(s"  homeAxis A result: $resultA")
    println(s"  homeAxis B result: $resultB")
    
    assert(resultA.isInstanceOf[Completed], s"homeAxis A should complete, got: $resultA")
    assert(resultB.isInstanceOf[Completed], s"homeAxis B should complete, got: $resultB")
  }

  // ==========================================================================
  // Test: positionAxis -- submit, observe transitions, verify completion
  // ==========================================================================
  
  test("positionAxis should move axis A and complete successfully") {
    val commandService = getCommandService
    val target = 500.0f  // encoder counts -- short move (~0.7s)
    
    // Read initial position from CurrentStateAxisA
    val initProbe = TestProbe[CurrentState]()
    val initSub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => initProbe.ref ! cs
    )
    val initState = initProbe.receiveMessage(stateTimeout)
    val initialPosition = initState(CurrentStateAxisACurrentState.positionKey).head
    println(s"  Initial axis A position: $initialPosition")
    initSub.cancel()
    
    // Submit positionAxis command
    val setup = makeSetup("positionAxis", "axis" -> "A", "target" -> target)
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    
    println(s"  Submit response: $submitResponse")
    assert(submitResponse.isInstanceOf[Started],
      s"positionAxis should return Started, got: $submitResponse")
    
    // Wait for command completion via queryFinal
    val finalResponse = Await.result(
      commandService.queryFinal(submitResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    
    println(s"  Final response: $finalResponse")
    assert(finalResponse.isInstanceOf[Completed],
      s"positionAxis should complete successfully, got: $finalResponse")
    
    // Verify final axis state shows Idle and inPosition
    val finalProbe = TestProbe[CurrentState]()
    val finalSub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => finalProbe.ref ! cs
    )
    val finalState = finalProbe.receiveMessage(stateTimeout)
    
    val axisState = finalState(CurrentStateAxisACurrentState.axisStateKey).head
    val inPosition = finalState(CurrentStateAxisACurrentState.inPositionKey).head
    val finalPosition = finalState(CurrentStateAxisACurrentState.positionKey).head
    
    println(s"  Final axis state: ${axisState.name}, inPosition: $inPosition, position: $finalPosition")
    assert(axisState.name == "idle", s"Axis should be idle after move, was: ${axisState.name}")
    assert(inPosition, "Axis should be inPosition after completed move")
    
    finalSub.cancel()
  }

  // ==========================================================================
  // Test: positionAxis state transitions via CurrentState
  // ==========================================================================

  test("positionAxis should publish Moving state transition") {
    val commandService = getCommandService
    val target = 700.0f
    
    // Start collecting CurrentStateAxisA before submitting command
    val axisStateName = StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(axisStateName),
      cs => probe.ref ! cs
    )
    
    // Drain any initial states
    try { probe.receiveMessage(1.second) } catch { case _: AssertionError => }
    
    // Submit positionAxis
    val setup = makeSetup("positionAxis", "axis" -> "A", "target" -> target)
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    assert(submitResponse.isInstanceOf[Started])
    
    // Collect states -- looking for a "moving" state
    var sawMoving = false
    var sawIdle = false
    val deadline = commandTimeout.fromNow
    
    while (!sawIdle && deadline.hasTimeLeft()) {
      try {
        val state = probe.receiveMessage(2.seconds)
        val axisState = state(CurrentStateAxisACurrentState.axisStateKey).head
        if (axisState.name == "moving") sawMoving = true
        if (axisState.name == "idle" && sawMoving) sawIdle = true
      } catch {
        case _: AssertionError => // timeout, keep waiting
      }
    }
    
    // Wait for command to finish 
    Await.result(
      commandService.queryFinal(submitResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    
    sub.cancel()
    
    println(s"  Saw Moving transition: $sawMoving")
    println(s"  Saw return to Idle: $sawIdle")
    assert(sawMoving, "Should have seen axis A in 'moving' state during positionAxis")
  }

  // ==========================================================================
  // Test: CommandStateAxisA during positionAxis
  // ==========================================================================
  
  test("positionAxis should publish CommandStateAxisA reflecting completion") {
    val commandService = getCommandService
    val target = 900.0f

    // Submit and wait for completion
    val setup = makeSetup("positionAxis", "axis" -> "A", "target" -> target)
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    assert(submitResponse.isInstanceOf[Started])

    val finalResponse = Await.result(
      commandService.queryFinal(submitResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    assert(finalResponse.isInstanceOf[Completed], s"positionAxis should complete, got: $finalResponse")

    // After completion, verify CommandStateAxisA reflects a quiescent state:
    // activeThread==0, moving==false, inPosition==true, no error.
    // This confirms the StatusMonitor→IS→CommandWatcher pipeline is working.
    val cmdStateName = StateName(CommandStateAxisACurrentState.eventKey.eventName.name)
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(cmdStateName),
      cs => probe.ref ! cs
    )

    val state = probe.receiveMessage(5.seconds)
    val thread = state(CommandStateAxisACurrentState.activeThreadKey).head
    val moving = state(CommandStateAxisACurrentState.movingKey).head
    val inPosition = state(CommandStateAxisACurrentState.inPositionKey).head

    sub.cancel()

    println(s"  Post-completion CommandState: activeThread=$thread, moving=$moving, inPosition=$inPosition")
    assert(thread == 0, s"activeThread should be 0 after completion, got: $thread")
    assert(!moving, "moving should be false after completion")
    assert(inPosition, "inPosition should be true after completion")
  }

  // ==========================================================================
  // Test: homeAxis -- submit, verify homing state, wait for completion
  // ==========================================================================
  
  test("homeAxis should home axis A and complete successfully") {
    val commandService = getCommandService
    
    // Subscribe to axis state to verify Homing transition
    val axisStateName = StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(axisStateName),
      cs => probe.ref ! cs
    )
    
    // Drain initial
    try { probe.receiveMessage(1.second) } catch { case _: AssertionError => }
    
    // Submit homeAxis
    val setup = makeSetup("homeAxis", "axis" -> "A")
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    
    println(s"  homeAxis submit response: $submitResponse")
    assert(submitResponse.isInstanceOf[Started],
      s"homeAxis should return Started, got: $submitResponse")
    
    // Collect states -- looking for "homing" transition
    var sawHoming = false
    val deadline = commandTimeout.fromNow
    
    while (!sawHoming && deadline.hasTimeLeft()) {
      try {
        val state = probe.receiveMessage(2.seconds)
        val axisState = state(CurrentStateAxisACurrentState.axisStateKey).head
        if (axisState.name == "homing") sawHoming = true
      } catch {
        case _: AssertionError =>
      }
    }
    
    // Wait for completion
    val finalResponse = Await.result(
      commandService.queryFinal(submitResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    
    sub.cancel()
    
    println(s"  Saw Homing transition: $sawHoming")
    println(s"  Final response: $finalResponse")
    assert(sawHoming, "Should have seen axis A in 'homing' state during homeAxis")
    assert(finalResponse.isInstanceOf[Completed],
      s"homeAxis should complete successfully, got: $finalResponse")
  }
  
  // ==========================================================================
  // Test: stopAxis -- start a move, then stop it
  // ==========================================================================
  
  test("stopAxis should halt an active positionAxis command") {
    val commandService = getCommandService
    // Rotating axes (400 counts/rev). Slow to 100 counts/sec so a 200-count
    // move takes ~2 seconds — enough time to interrupt while keeping recovery fast.
    setAxisSpeed(commandService, "A", 100.0f)

    val farTarget = 200.0f

    // Start a long (slow) move
    val moveSetup = makeSetup("positionAxis", "axis" -> "A", "target" -> farTarget)
    val moveResponse = Await.result(commandService.submit(moveSetup), 5.seconds)
    assert(moveResponse.isInstanceOf[Started], s"positionAxis should return Started, got: $moveResponse")
    println(s"  Started positionAxis to $farTarget")

    // Wait for Moving state to be confirmed via CurrentState before sending stop
    collectStatesUntil(
      commandService,
      StateName(CurrentStateAxisACurrentState.eventKey.eventName.name),
      s => s(CurrentStateAxisACurrentState.axisStateKey).head.name == "moving"
    )
    println(s"  Moving state confirmed")
    
    // Submit stop
    val stopSetup = makeSetup("stopAxis", "axis" -> "A")
    val stopResponse = Await.result(commandService.submit(stopSetup), 5.seconds)
    println(s"  stopAxis submit response: $stopResponse")
    assert(stopResponse.isInstanceOf[Started],
      s"stopAxis should return Started, got: $stopResponse")
    
    // The original positionAxis should eventually report Error (interrupted)
    val moveResult = Await.result(
      commandService.queryFinal(moveResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  positionAxis final result after stop: $moveResult")
    // The move should have been interrupted
    assert(moveResult.isInstanceOf[Error],
      s"positionAxis should report Error after stop, got: $moveResult")
    
    // The stopAxis itself should complete
    val stopResult = Await.result(
      commandService.queryFinal(stopResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  stopAxis final result: $stopResult")
    assert(stopResult.isInstanceOf[Completed],
      s"stopAxis should complete, got: $stopResult")
    
    // Verify axis is not moving
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(StateName(CommandStateAxisACurrentState.eventKey.eventName.name)),
      cs => probe.ref ! cs
    )
    val cmdState = probe.receiveMessage(stateTimeout)
    val moving = cmdState(CommandStateAxisACurrentState.movingKey).head
    println(s"  Axis A moving after stop: $moving")
    assert(!moving, "Axis should not be moving after stopAxis")
    sub.cancel()
    setAxisSpeed(commandService, "A", 100.0f)  // restore normal speed
  }
  // Verifies the full interruption sequence:
  //   HX kills the move thread → positionAxis reports Error(interrupted)
  //   #StopX executes cleanly → stopAxis reports Completed
  //   Axis ends in Idle state → a subsequent move succeeds (controller is clean)
  // ==========================================================================

  test("stopAxis should interrupt an active positionAxis and leave axis ready for new commands") {
    val commandService = getCommandService
    setAxisSpeed(commandService, "A", 100.0f)
    val farTarget = 200.0f

    // Start a long (slow) move
    val moveSetup = makeSetup("positionAxis", "axis" -> "A", "target" -> farTarget)
    val moveResponse = Await.result(commandService.submit(moveSetup), 5.seconds)
    assert(moveResponse.isInstanceOf[Started], s"positionAxis should return Started, got: $moveResponse")
    println(s"  Started positionAxis A to $farTarget")

    // Wait for Moving state to be confirmed via CurrentState
    val states = collectStatesUntil(
      commandService,
      StateName(CurrentStateAxisACurrentState.eventKey.eventName.name),
      s => s(CurrentStateAxisACurrentState.axisStateKey).head.name == "moving"
    )
    println(s"  Moving confirmed: ${states.lastOption.map(_(CurrentStateAxisACurrentState.axisStateKey).head.name)}")

    // Submit stopAxis — arrives while axis is Moving
    val stopSetup = makeSetup("stopAxis", "axis" -> "A")
    val stopResponse = Await.result(commandService.submit(stopSetup), 5.seconds)
    assert(stopResponse.isInstanceOf[Started], s"stopAxis should return Started, got: $stopResponse")
    println(s"  stopAxis A submitted")

    // The interrupted positionAxis should report Error
    val moveResult = Await.result(
      commandService.queryFinal(moveResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  positionAxis result after stop: $moveResult")
    assert(moveResult.isInstanceOf[Error],
      s"Interrupted positionAxis should report Error, got: $moveResult")

    // The stopAxis itself should complete
    val stopResult = Await.result(
      commandService.queryFinal(stopResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  stopAxis result: $stopResult")
    assert(stopResult.isInstanceOf[Completed],
      s"stopAxis should complete, got: $stopResult")

    // Axis should be Idle after stop
    val idleStates = collectStatesUntil(
      commandService,
      StateName(CurrentStateAxisACurrentState.eventKey.eventName.name),
      s => s(CurrentStateAxisACurrentState.axisStateKey).head.name == "idle"
    )
    val finalAxisState = idleStates.lastOption
      .map(_(CurrentStateAxisACurrentState.axisStateKey).head.name)
      .getOrElse("unknown")
    println(s"  Axis A state after stop: $finalAxisState")
    assert(finalAxisState == "idle", s"Axis A should be idle after stop, was: $finalAxisState")

    // Issue a new move to verify the controller is in a clean state
    val recoverySetup = makeSetup("positionAxis", "axis" -> "A", "target" -> 500.0f)
    val recoveryResponse = Await.result(commandService.submit(recoverySetup), 5.seconds)
    assert(recoveryResponse.isInstanceOf[Started], s"Recovery move should start, got: $recoveryResponse")
    val recoveryResult = Await.result(
      commandService.queryFinal(recoveryResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  Recovery positionAxis result: $recoveryResult")
    assert(recoveryResult.isInstanceOf[Completed],
      s"Recovery move should complete after interruption, got: $recoveryResult")
    setAxisSpeed(commandService, "A", 100.0f)  // restore normal speed
  }

  // ==========================================================================
  // Test: positionAxis interrupts an active positionAxis (Moving state)
  // Verifies that a new move correctly preempts an in-progress move:
  //   HX + ST kills the move thread → original positionAxis reports Error(interrupted)
  //   New positionAxis executes cleanly → reports Completed at the new target
  // ==========================================================================

  test("positionAxis should interrupt an active positionAxis and complete to the new target") {
    val commandService = getCommandService
    setAxisSpeed(commandService, "B", 100.0f)
    val farTarget    = 200.0f  // within one revolution; slow speed makes it take ~2s
    val newTarget    = 100.0f  // new target for interrupting command

    // Start a long (slow) move
    val move1Setup = makeSetup("positionAxis", "axis" -> "B", "target" -> farTarget)
    val move1Response = Await.result(commandService.submit(move1Setup), 5.seconds)
    assert(move1Response.isInstanceOf[Started], s"First positionAxis should start, got: $move1Response")
    println(s"  Started positionAxis B to $farTarget")

    // Wait for Moving state
    collectStatesUntil(
      commandService,
      StateName(CurrentStateAxisBCurrentState.eventKey.eventName.name),
      s => s(CurrentStateAxisBCurrentState.axisStateKey).head.name == "moving"
    )
    println(s"  Moving confirmed on axis B")

    // Submit a new positionAxis — interrupts the running move
    val move2Setup = makeSetup("positionAxis", "axis" -> "B", "target" -> newTarget)
    val move2Response = Await.result(commandService.submit(move2Setup), 5.seconds)
    assert(move2Response.isInstanceOf[Started], s"Second positionAxis should start, got: $move2Response")
    println(s"  Submitted interrupting positionAxis B to $newTarget")

    // Original move should report Error (interrupted)
    val move1Result = Await.result(
      commandService.queryFinal(move1Response.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  Original positionAxis result: $move1Result")
    assert(move1Result.isInstanceOf[Error],
      s"Interrupted positionAxis should report Error, got: $move1Result")

    // New move should complete at the new target
    val move2Result = Await.result(
      commandService.queryFinal(move2Response.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  Interrupting positionAxis result: $move2Result")
    assert(move2Result.isInstanceOf[Completed],
      s"Interrupting positionAxis should complete, got: $move2Result")

    // Verify axis B is at the new target in Idle state
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisBCurrentState.eventKey.eventName.name)),
      cs => probe.ref ! cs
    )
    val finalState = probe.receiveMessage(stateTimeout)
    sub.cancel()
    val pos = finalState(CurrentStateAxisBCurrentState.positionKey).head
    val axisState = finalState(CurrentStateAxisBCurrentState.axisStateKey).head
    val inPos = finalState(CurrentStateAxisBCurrentState.inPositionKey).head
    println(s"  Axis B final: state=${axisState.name}, pos=$pos, inPos=$inPos")
    assert(axisState.name == "idle", s"Axis B should be idle, was: ${axisState.name}")
    assert(inPos, "Axis B should be inPosition at new target")
    assert(pos == newTarget.toDouble, s"Axis B should be at $newTarget, was: $pos")
    setAxisSpeed(commandService, "B", 100.0f)  // restore normal speed
  }

  // ==========================================================================
  // Test: positionAxis on axis B -- verify correct axis mapping
  // ==========================================================================
  
  test("positionAxis should work on axis B independently") {
    val commandService = getCommandService
    val target = 500.0f
    
    // Submit positionAxis for axis B
    val setup = makeSetup("positionAxis", "axis" -> "B", "target" -> target)
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    
    println(s"  positionAxis B submit response: $submitResponse")
    assert(submitResponse.isInstanceOf[Started])
    
    // Wait for completion
    val finalResponse = Await.result(
      commandService.queryFinal(submitResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    
    println(s"  positionAxis B final response: $finalResponse")
    assert(finalResponse.isInstanceOf[Completed],
      s"positionAxis B should complete, got: $finalResponse")
    
    // Verify axis B state
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisBCurrentState.eventKey.eventName.name)),
      cs => probe.ref ! cs
    )
    val finalState = probe.receiveMessage(stateTimeout)
    val axisState = finalState(CurrentStateAxisBCurrentState.axisStateKey).head
    val inPosition = finalState(CurrentStateAxisBCurrentState.inPositionKey).head
    
    println(s"  Axis B final: state=${axisState.name}, inPosition=$inPosition")
    assert(axisState.name == "idle", s"Axis B should be idle, was: ${axisState.name}")
    assert(inPosition, "Axis B should be inPosition")
    sub.cancel()
  }

  // ==========================================================================
  // Test: Sequential commands -- move, then move again
  // ==========================================================================
  
  test("sequential positionAxis commands should both complete") {
    val commandService = getCommandService
    
    // First move
    val setup1 = makeSetup("positionAxis", "axis" -> "A", "target" -> 300.0f)
    val response1 = Await.result(commandService.submit(setup1), 5.seconds)
    assert(response1.isInstanceOf[Started])
    
    val result1 = Await.result(
      commandService.queryFinal(response1.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  Move 1 to 300: $result1")
    assert(result1.isInstanceOf[Completed], s"First move should complete, got: $result1")
    
    // Second move
    val setup2 = makeSetup("positionAxis", "axis" -> "A", "target" -> 600.0f)
    val response2 = Await.result(commandService.submit(setup2), 5.seconds)
    assert(response2.isInstanceOf[Started])
    
    val result2 = Await.result(
      commandService.queryFinal(response2.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  Move 2 to 600: $result2")
    assert(result2.isInstanceOf[Completed], s"Second move should complete, got: $result2")
  }

  // ==========================================================================
  // Test: configAxis immediate command through same interface
  // ==========================================================================

  test("configAxis immediate command should complete through CommandService") {
    val commandService = getCommandService
    
    // Build configAxis with speed parameter
    var setup = Setup(hcdPrefix, CommandName("configAxis"), None)
    setup = setup.add(ConfigAxisCommand.axisKey.set(Choice("A")))
    setup = setup.add(ConfigAxisCommand.velocityKey.set(800.0f))
    
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    println(s"  configAxis submit: $submitResponse")
    assert(submitResponse.isInstanceOf[Started],
      s"configAxis should be accepted, got: $submitResponse")
    
    // Immediate commands update CRM quickly
    val finalResponse = Await.result(
      commandService.queryFinal(submitResponse.runId)(Timeout(5.seconds)),
      5.seconds
    )
    println(s"  configAxis final: $finalResponse")
    assert(finalResponse.isInstanceOf[Completed],
      s"configAxis should complete, got: $finalResponse")
  }

  // ==========================================================================
  // Test: Concurrent multi-axis motion -- move A and B simultaneously
  // ==========================================================================

  test("positionAxis should move A and B concurrently") {
    val commandService = getCommandService
    // Rotating axes: 400 counts/rev. Choose targets well within [1, 399] so the
    // approach algorithm does not wrap them back to 0 or produce a zero-distance move.
    val targetA = 100.0f
    val targetB = 150.0f

    // Submit both moves concurrently
    val setupA = makeSetup("positionAxis", "axis" -> "A", "target" -> targetA)
    val setupB = makeSetup("positionAxis", "axis" -> "B", "target" -> targetB)

    val futA = commandService.submit(setupA)
    val futB = commandService.submit(setupB)

    val respA = Await.result(futA, 5.seconds)
    val respB = Await.result(futB, 5.seconds)
    println(s"  positionAxis A submit: $respA")
    println(s"  positionAxis B submit: $respB")

    assert(respA.isInstanceOf[Started], s"positionAxis A should start, got: $respA")
    assert(respB.isInstanceOf[Started], s"positionAxis B should start, got: $respB")

    // Wait for both to complete in parallel
    val finalA = commandService.queryFinal(respA.runId)(Timeout(commandTimeout))
    val finalB = commandService.queryFinal(respB.runId)(Timeout(commandTimeout))

    val resultA = Await.result(finalA, commandTimeout)
    val resultB = Await.result(finalB, commandTimeout)
    println(s"  positionAxis A result: $resultA")
    println(s"  positionAxis B result: $resultB")

    assert(resultA.isInstanceOf[Completed], s"positionAxis A should complete, got: $resultA")
    assert(resultB.isInstanceOf[Completed], s"positionAxis B should complete, got: $resultB")

    // Verify both axes reached their targets
    val probeA = TestProbe[CurrentState]()
    val subA = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => probeA.ref ! cs
    )
    val stateA = probeA.receiveMessage(stateTimeout)
    val posA = stateA(CurrentStateAxisACurrentState.positionKey).head
    val axisStateA = stateA(CurrentStateAxisACurrentState.axisStateKey).head
    val inPosA = stateA(CurrentStateAxisACurrentState.inPositionKey).head
    subA.cancel()

    val probeB = TestProbe[CurrentState]()
    val subB = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisBCurrentState.eventKey.eventName.name)),
      cs => probeB.ref ! cs
    )
    val stateB = probeB.receiveMessage(stateTimeout)
    val posB = stateB(CurrentStateAxisBCurrentState.positionKey).head
    val axisStateB = stateB(CurrentStateAxisBCurrentState.axisStateKey).head
    val inPosB = stateB(CurrentStateAxisBCurrentState.inPositionKey).head
    subB.cancel()

    println(s"  Axis A final: state=${axisStateA.name}, pos=$posA, inPos=$inPosA")
    println(s"  Axis B final: state=${axisStateB.name}, pos=$posB, inPos=$inPosB")

    assert(axisStateA.name == "idle", s"Axis A should be idle, was: ${axisStateA.name}")
    assert(axisStateB.name == "idle", s"Axis B should be idle, was: ${axisStateB.name}")
    assert(inPosA, "Axis A should be inPosition")
    assert(inPosB, "Axis B should be inPosition")
    assert(posA == targetA.toDouble, s"Axis A position should be $targetA, was: $posA")
    assert(posB == targetB.toDouble, s"Axis B position should be $targetB, was: $posB")
  }

  // ==========================================================================
  // Test: Zero-distance move should complete immediately without controller motion
  // When the axis is already at the requested target, the HCD should detect this
  // and return Completed without invoking the embedded motion program.
  // ==========================================================================

  test("positionAxis to current position should complete immediately") {
    val commandService = getCommandService

    // Read current position of axis A
    val initProbe = TestProbe[CurrentState]()
    val initSub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => initProbe.ref ! cs
    )
    val initState = initProbe.receiveMessage(stateTimeout)
    val currentPos = initState(CurrentStateAxisACurrentState.positionKey).head
    initSub.cancel()
    println(s"  Zero-motion: current A position = $currentPos")

    // Request move to where we already are
    val setup = makeSetup("positionAxis", "axis" -> "A", "target" -> currentPos.toFloat)
    val startTime = System.currentTimeMillis()
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    println(s"  Zero-motion submit: $submitResponse")

    // Should complete immediately — either Started+Completed or Completed directly
    val finalResponse = submitResponse match {
      case started: Started =>
        Await.result(
          commandService.queryFinal(started.runId)(Timeout(5.seconds)),
          5.seconds
        )
      case other => other
    }
    val elapsed = System.currentTimeMillis() - startTime
    println(s"  Zero-motion result: $finalResponse (${elapsed}ms)")

    assert(finalResponse.isInstanceOf[Completed],
      s"Zero-distance move should complete, got: $finalResponse")
    assert(elapsed < 2000, s"Zero-distance move should complete quickly, took ${elapsed}ms")
  }

  // ==========================================================================
  // Test: Short move completes correctly despite rate adaptation
  // A ~200 count move should complete in <0.5s. This validates that the
  // StatusMonitor switches from standby (1Hz) to action (10Hz) rate fast
  // enough to track motion and detect completion on very short moves.
  // ==========================================================================

  test("positionAxis short move should complete and track correctly") {
    val commandService = getCommandService
    val target = 200.0f  // ~0.3s move — tests rate adaptation timing

    val setup = makeSetup("positionAxis", "axis" -> "A", "target" -> target)
    val submitResponse = Await.result(commandService.submit(setup), 5.seconds)
    println(s"  Short move submit: $submitResponse")
    assert(submitResponse.isInstanceOf[Started],
      s"positionAxis should return Started, got: $submitResponse")

    val finalResponse = Await.result(
      commandService.queryFinal(submitResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  Short move result: $finalResponse")
    assert(finalResponse.isInstanceOf[Completed],
      s"Short move should complete, got: $finalResponse")

    // Verify position reached and state returned to idle
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => probe.ref ! cs
    )
    val state = probe.receiveMessage(stateTimeout)
    val pos = state(CurrentStateAxisACurrentState.positionKey).head
    val axisState = state(CurrentStateAxisACurrentState.axisStateKey).head
    val inPosition = state(CurrentStateAxisACurrentState.inPositionKey).head
    sub.cancel()

    println(s"  Short move final: state=${axisState.name}, pos=$pos, inPos=$inPosition")
    assert(axisState.name == "idle", s"Should be idle after short move, was: ${axisState.name}")
    assert(inPosition, "Should be inPosition after short move")
    assert(pos == target.toDouble, s"Position should be $target, was: $pos")
  }

  // ==========================================================================
  // Test: trackAxis -- start tracking, verify state, stop
  // Tracking is only implemented for axis A on the test bench.
  // The #TrackA program expects Atarget[0] (position) and Atarget[1] (velocity).
  // It sets JG at the target velocity, applies IP position correction, then ENDs.
  // The motor continues jogging until stopAxis is issued.
  // ==========================================================================

  test("trackAxis should enter Tracking state and stopAxis should return to Idle") {
    val commandService = getCommandService

    // First, read the current position of axis A so we can start tracking near it
    val initProbe = TestProbe[CurrentState]()
    val initSub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => initProbe.ref ! cs
    )
    val initState = initProbe.receiveMessage(stateTimeout)
    val currentPos = initState(CurrentStateAxisACurrentState.positionKey).head
    initSub.cancel()
    println(s"  trackAxis: current A position = $currentPos")

    // Start tracking: position near current, slow rotation rate
    val trackSetup = makeSetup("trackAxis", "axis" -> "A",
      "target1" -> currentPos.toFloat, "target2" -> 20.0f)
    val trackResponse = Await.result(commandService.submit(trackSetup), 5.seconds)
    println(s"  trackAxis submit: $trackResponse")
    assert(trackResponse.isInstanceOf[Started],
      s"trackAxis should return Started, got: $trackResponse")

    // The trackAxis command should complete (the #TrackA program runs and ENDs)
    val trackResult = Await.result(
      commandService.queryFinal(trackResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  trackAxis final: $trackResult")
    assert(trackResult.isInstanceOf[Completed],
      s"trackAxis should complete, got: $trackResult")

    // Verify axis A is in Tracking state (not Idle, not Moving)
    val trackProbe = TestProbe[CurrentState]()
    val trackSub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => trackProbe.ref ! cs
    )
    val trackState = trackProbe.receiveMessage(stateTimeout)
    val trackAxisState = trackState(CurrentStateAxisACurrentState.axisStateKey).head
    trackSub.cancel()
    println(s"  trackAxis: axisState = ${trackAxisState.name}")
    assert(trackAxisState.name == "tracking",
      s"Axis A should be tracking, was: ${trackAxisState.name}")

    // Let it track for a bit so we can verify motion
    Thread.sleep(1000)

    // Read position — it should have changed (motor is jogging)
    val motionProbe = TestProbe[CurrentState]()
    val motionSub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => motionProbe.ref ! cs
    )
    val motionState = motionProbe.receiveMessage(stateTimeout)
    val trackingPos = motionState(CurrentStateAxisACurrentState.positionKey).head
    motionSub.cancel()
    println(s"  trackAxis: position after 1s of tracking = $trackingPos (started at $currentPos)")

    // Now stop
    val stopSetup = makeSetup("stopAxis", "axis" -> "A")
    val stopResponse = Await.result(commandService.submit(stopSetup), 5.seconds)
    println(s"  stopAxis submit: $stopResponse")
    assert(stopResponse.isInstanceOf[Started],
      s"stopAxis should return Started, got: $stopResponse")

    val stopResult = Await.result(
      commandService.queryFinal(stopResponse.runId)(Timeout(commandTimeout)),
      commandTimeout
    )
    println(s"  stopAxis final: $stopResult")
    assert(stopResult.isInstanceOf[Completed],
      s"stopAxis should complete, got: $stopResult")

    // Verify axis A returned to Idle and is no longer moving
    Thread.sleep(500) // let state settle
    val finalProbe = TestProbe[CurrentState]()
    val finalSub = commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      cs => finalProbe.ref ! cs
    )
    val finalState = finalProbe.receiveMessage(stateTimeout)
    val finalAxisState = finalState(CurrentStateAxisACurrentState.axisStateKey).head
    finalSub.cancel()
    println(s"  After stop: axisState = ${finalAxisState.name}")
    assert(finalAxisState.name == "idle",
      s"Axis A should be idle after stopAxis, was: ${finalAxisState.name}")

    // Verify motor is not moving via CommandStateAxisA
    val cmdProbe = TestProbe[CurrentState]()
    val cmdSub = commandService.subscribeCurrentState(
      Set(StateName(CommandStateAxisACurrentState.eventKey.eventName.name)),
      cs => cmdProbe.ref ! cs
    )
    val cmdState = cmdProbe.receiveMessage(stateTimeout)
    val moving = cmdState(CommandStateAxisACurrentState.movingKey).head
    cmdSub.cancel()
    println(s"  After stop: moving = $moving")
    assert(!moving, "Axis A should not be moving after stopAxis")
  }
  // ── I/O Tests ────────────────────────────────────────────────────────────

  val ioStateName = StateName(InputOutputStateCurrentState.eventKey.eventName.name)

  /**
   * Wait for one InputOutputState publication and return it.
   */
  private def receiveIOState(commandService: csw.command.api.scaladsl.CommandService): CurrentState =
    val probe = TestProbe[CurrentState]()
    val sub = commandService.subscribeCurrentState(Set(ioStateName), cs => probe.ref ! cs)
    val state = probe.receiveMessage(stateTimeout)
    sub.cancel()
    state

  test("InputOutputState should be published with correct array sizes") {
    val commandService = getCommandService
    val ioState = receiveIOState(commandService)
    val di   = ioState(InputOutputStateCurrentState.digitalInputsKey).values
    val dout = ioState(InputOutputStateCurrentState.digitalOutputsKey).values
    val ai   = ioState(InputOutputStateCurrentState.analogInputsKey).head.data
    println(s"  digitalInputs  length = ${di.length}")
    println(s"  digitalOutputs length = ${dout.length}")
    println(s"  analogInputs   length = ${ai.length}")
    assert(di.length == 16,  s"digitalInputs should have 16 elements, got ${di.length}")
    assert(dout.length == 16, s"digitalOutputs should have 16 elements, got ${dout.length}")
    assert(ai.length == 8,   s"analogInputs should have 8 elements, got ${ai.length}")
  }

  test("setBit should set and clear a digital output") {
    val commandService = getCommandService
    val address = 1  // @OUT[1] — verified safe on the lab controller

    // ── Set bit 1 ──
    // Subscribe before sending so we don't miss the publication triggered by the QR poll
    val setProbe = TestProbe[CurrentState]()
    val setSub = commandService.subscribeCurrentState(Set(ioStateName), cs => setProbe.ref ! cs)

    var setup = Setup(hcdPrefix, CommandName("setBit"), None)
    setup = setup.add(SetBitCommand.addressKey.set(address))
    setup = setup.add(SetBitCommand.valueKey.set(1))
    val setSubmit = Await.result(commandService.submit(setup), 5.seconds)
    println(s"  setBit address=$address value=1: $setSubmit")
    assert(setSubmit.isInstanceOf[Started],
      s"setBit should be accepted, got: $setSubmit")
    val setResponse = Await.result(
      commandService.queryFinal(setSubmit.runId)(Timeout(5.seconds)), 5.seconds)
    println(s"  setBit value=1 final: $setResponse")
    assert(setResponse.isInstanceOf[Completed],
      s"setBit value=1 should complete, got: $setResponse")

    // Wait for the next InputOutputState publication (may be up to 1s at standby poll rate)
    val setIOState = setProbe.receiveMessage(stateTimeout)
    setSub.cancel()
    val outputsAfterSet = setIOState(InputOutputStateCurrentState.digitalOutputsKey).values
    println(s"  digitalOutputs after set: ${outputsAfterSet.take(8).mkString("[", ",", "]")}")
    assert(outputsAfterSet(address - 1),
      s"digitalOutputs(${address-1}) should be true after setBit value=1")

    // ── Clear bit 1 ──
    val clrProbe = TestProbe[CurrentState]()
    val clrSub = commandService.subscribeCurrentState(Set(ioStateName), cs => clrProbe.ref ! cs)

    var clearSetup = Setup(hcdPrefix, CommandName("setBit"), None)
    clearSetup = clearSetup.add(SetBitCommand.addressKey.set(address))
    clearSetup = clearSetup.add(SetBitCommand.valueKey.set(0))
    val clearSubmit = Await.result(commandService.submit(clearSetup), 5.seconds)
    println(s"  setBit address=$address value=0: $clearSubmit")
    assert(clearSubmit.isInstanceOf[Started],
      s"setBit should be accepted, got: $clearSubmit")
    val clearResponse = Await.result(
      commandService.queryFinal(clearSubmit.runId)(Timeout(5.seconds)), 5.seconds)
    println(s"  setBit value=0 final: $clearResponse")
    assert(clearResponse.isInstanceOf[Completed],
      s"setBit value=0 should complete, got: $clearResponse")

    val clrIOState = clrProbe.receiveMessage(stateTimeout)
    clrSub.cancel()
    val outputsAfterClear = clrIOState(InputOutputStateCurrentState.digitalOutputsKey).values
    println(s"  digitalOutputs after clear: ${outputsAfterClear.take(8).mkString("[", ",", "]")}")
    assert(!outputsAfterClear(address - 1),
      s"digitalOutputs(${address-1}) should be false after setBit value=0")
  }

  test("analogInputs should be populated from MG @AN[n] polling") {
    val commandService = getCommandService

    // Allow at least two AI poll cycles (1Hz timer) for values to propagate
    Thread.sleep(2200)

    val ioState = receiveIOState(commandService)
    val ai = ioState(InputOutputStateCurrentState.analogInputsKey).head.data
    println(s"  analogInputs: ${ai.zipWithIndex.map { case (v, i) => s"AN${i+1}=${v}V" }.mkString(", ")}")

    // AN1 and AN2 are wired on the lab controller and read ~2.58V
    assert(Math.abs(ai(0)) > 0.1f,
      s"AN1 should be non-zero (wired on lab controller), got ${ai(0)}V")
    assert(Math.abs(ai(1)) > 0.1f,
      s"AN2 should be non-zero (wired on lab controller), got ${ai(1)}V")
    // All channels within ±10V ADC range
    ai.foreach { v =>
      assert(v >= -10.0f && v <= 10.0f,
        s"All AI values should be within ±10V range, got $v")
    }
  }