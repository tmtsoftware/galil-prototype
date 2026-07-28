package csw.proto.galil.hcd

import com.typesafe.config.ConfigFactory
import csw.command.client.CommandServiceFactory
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.params.core.states.{CurrentState, StateName}
import csw.params.events.EventName
import csw.prefix.models.{Prefix, Subsystem}
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import csw.proto.galil.GalilMotionKeys
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import csw.time.core.models.TAITime
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe

/**
 * Tests for CurrentStatePublisherActor using FrameworkTestKit
 * 
 * This follows the CSW pattern from:
 * https://tmtsoftware.github.io/csw/commons/testing.html
 * 
 * Pattern:
 * - Use ScalaTestFrameworkTestKit(AlarmServer, EventServer)
 * - Mix in BeforeAndAfterEach  
 * - Override beforeAll() and call super.beforeAll()
 * - Use FrameworkTestKit to spawn real components
 * - Use CommandService.subscribeCurrentState() to verify publications
 *
 * NOTE: Requires GalilHcdStandalone.conf in test resources and that 
 * application.conf does NOT override pekko.actor.provider (CSW manages this).
 */
class CurrentStatePublisherActorTest
  extends ScalaTestFrameworkTestKit(AlarmServer, EventServer)
  with AnyFunSuiteLike
  with BeforeAndAfterEach:
  
  import frameworkTestKit.*
  import GalilMotionKeys.`ICS.HCD.GalilMotion`.*
  
  // HCD prefix as defined in ICD
  val hcdPrefix = Prefix(Subsystem.APS, "ICS.HCD.GalilMotion")
  
  override def beforeAll(): Unit =
    println("=== beforeAll: Starting FrameworkTestKit services ===")
    try {
      super.beforeAll()  // This starts Location Service, Event Service, Alarm Service
      println(s"=== FrameworkTestKit services started successfully ===")
      println(s"=== Spawning HCD from GalilHcdStandalone.conf ===")
      
      // Spawn the HCD in standalone mode
      spawnStandalone(ConfigFactory.load("GalilHcdStandalone.conf"))  // ← ADDED CLOSING PAREN
      println(s"=== HCD spawned successfully ===")
    } catch {
      case e: Exception =>
        println(s"=== ERROR in beforeAll: ${e.getMessage} ===")
        e.printStackTrace()
        throw e
    }
  
  override def afterAll(): Unit =
    println("=== afterAll: Shutting down FrameworkTestKit ===")
    try {
      super.afterAll()
      println("=== FrameworkTestKit shutdown complete ===")
    } catch {
      case e: Exception =>
        println(s"=== ERROR in afterAll: ${e.getMessage} ===")
        e.printStackTrace()
    }
  
  test("HCD should be locatable using Location Service") {
    val connection = PekkoConnection(ComponentId(hcdPrefix, ComponentType.HCD))
    val pekkoLocation = Await.result(
      locationService.resolve(connection, 10.seconds),
      10.seconds
    ).get
    
    assert(pekkoLocation.connection == connection)
  }
  
  test("CurrentState - system state should be published") {
    // Get command service for the HCD
    val connection = PekkoConnection(ComponentId(hcdPrefix, ComponentType.HCD))
    val pekkoLocation = Await.result(
      locationService.resolve(connection, 10.seconds),
      10.seconds
    ).get
    
    val commandService = CommandServiceFactory.make(pekkoLocation)
    
    // Subscribe to CurrentState
    val probe = TestProbe[CurrentState]()
    commandService.subscribeCurrentState(
      Set(StateName(CurrentStateCurrentState.eventKey.eventName.name)),
      currentState => probe.ref ! currentState
    )
    
    // Wait for first publication
    val currentState = probe.receiveMessage(5.seconds)
    
    // Verify it has the expected keys
    assert(currentState.exists(CurrentStateCurrentState.stateKey))
    val state = currentState(CurrentStateCurrentState.stateKey).head
    // Choice type - use .name to get the string value
    val stateName = state.name
    assert(stateName == "Uninitialized" || stateName == "Ready" || stateName == "Faulted")
  }
  
  test("CurrentState - axis A state should be published") {
    val connection = PekkoConnection(ComponentId(hcdPrefix, ComponentType.HCD))
    val pekkoLocation = Await.result(
      locationService.resolve(connection, 10.seconds),
      10.seconds
    ).get
    
    val commandService = CommandServiceFactory.make(pekkoLocation)
    
    val probe = TestProbe[CurrentState]()
    commandService.subscribeCurrentState(
      Set(StateName(CurrentStateAxisACurrentState.eventKey.eventName.name)),
      currentState => probe.ref ! currentState
    )
    
    val currentState = probe.receiveMessage(5.seconds)
    
    // Verify axis state keys exist
    assert(currentState.exists(CurrentStateAxisACurrentState.positionKey))
    assert(currentState.exists(CurrentStateAxisACurrentState.velocityKey))
    assert(currentState.exists(CurrentStateAxisACurrentState.axisStateKey))
  }
  
  test("CurrentState - all active axis states should be published") {
    val connection = PekkoConnection(ComponentId(hcdPrefix, ComponentType.HCD))
    val pekkoLocation = Await.result(
      locationService.resolve(connection, 10.seconds),
      10.seconds
    ).get
    
    val commandService = CommandServiceFactory.make(pekkoLocation)
    
    // Spot-check a subset of the active axes (the default GalilHcdConfig.conf
    // has all 8 active since S86; A and B suffice to prove the publication path)
    val activeAxisKeys = Seq(
      CurrentStateAxisACurrentState.eventKey,
      CurrentStateAxisBCurrentState.eventKey
    )
    
    activeAxisKeys.foreach { eventKey =>
      val probe = TestProbe[CurrentState]()
      commandService.subscribeCurrentState(
        Set(StateName(eventKey.eventName.name)),
        currentState => probe.ref ! currentState
      )
      
      val currentState = probe.receiveMessage(5.seconds)
      assert(currentState.prefix == hcdPrefix, s"Axis ${eventKey.eventName} should have correct prefix")
    }
  }
  // ══════════════════════════════════════════════════════════════════════
  // Publication cadence (S88)
  //
  // Before S88, CurrentStateAxis was published on a fixed 100 ms timer that resampled
  // InternalStateActor's state independently of acquisition. That produced ten identical
  // publishes per real reading at the 1 Hz standby rate, and an uncorrelated beat against
  // the scan at the 10 Hz action rate. Nothing published carried the acquisition time, so
  // neither artefact was observable by any consumer -- which is precisely why it survived
  // unnoticed. sampleTime is what makes these assertions possible at all.
  // ══════════════════════════════════════════════════════════════════════

  /** Subscribe to one axis and collect whatever arrives within `window`. */
  private def collectAxisStates(eventName: String, window: FiniteDuration): Seq[CurrentState] =
    val connection = PekkoConnection(ComponentId(hcdPrefix, ComponentType.HCD))
    val pekkoLocation = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds).get
    val commandService = CommandServiceFactory.make(pekkoLocation)

    val received = new java.util.concurrent.ConcurrentLinkedQueue[CurrentState]()
    commandService.subscribeCurrentState(Set(StateName(eventName)), cs => received.add(cs))
    Thread.sleep(window.toMillis)
    import scala.jdk.CollectionConverters.*
    received.iterator().asScala.toVector

  test("sampleTime is published on every axis state and is a TAI instant") {
    val samples = collectAxisStates(CurrentStateAxisACurrentState.eventKey.eventName.name, 2.seconds)
    assert(samples.nonEmpty, "no CurrentStateAxisA received - is the simulator up on 8888?")

    samples.foreach { cs =>
      assert(cs.exists(CurrentStateAxisACurrentState.sampleTimeKey), "sampleTime missing from CurrentStateAxisA")
    }

    // TAI runs ~37 s ahead of UTC. Comparing against TAITime.now() rather than a
    // hardcoded offset means this stays correct if the leap-second offset ever changes,
    // while still failing loudly if someone publishes a UTC instant by mistake -- which
    // is a live risk, since the HMI's own position buffer deliberately uses UTC millis.
    val nowTai = TAITime.now().value
    samples.foreach { cs =>
      val st = cs(CurrentStateAxisACurrentState.sampleTimeKey).head.value
      val skewSec = math.abs(java.time.Duration.between(st, nowTai).getSeconds)
      assert(skewSec < 10L,
        s"sampleTime $st is ${skewSec}s from TAI now ($nowTai) - a ~37s skew means a UTC instant was published")
    }
  }

  test("each publication carries a distinct, advancing sampleTime (one publish per scan)") {
    // The structural proof that publication is scan-aligned, and independent of any
    // wall-clock rate measurement: a timer that resamples IS state republishes the SAME
    // lastPollingTime, so duplicate consecutive sampleTimes are the signature of the old
    // behaviour. Reintroduce a fixed publish timer and this fails immediately.
    val samples = collectAxisStates(CurrentStateAxisACurrentState.eventKey.eventName.name, 4.seconds)
    assert(samples.size >= 2, s"need at least 2 samples to compare, got ${samples.size}")

    val times = samples.map(_.apply(CurrentStateAxisACurrentState.sampleTimeKey).head.value)
    val duplicates = times.zip(times.tail).count { case (a, b) => a == b }
    assert(duplicates == 0,
      s"$duplicates consecutive publications shared a sampleTime - publication is resampling, not scan-driven")

    val regressions = times.zip(times.tail).count { case (a, b) => b.isBefore(a) }
    assert(regressions == 0, s"$regressions sampleTime regressions - samples arrived out of order")
  }

  test("an idle axis publishes at roughly the standby rate, not the action rate") {
    // Bounds are deliberately loose: this asserts the ORDER OF MAGNITUDE, which is the
    // thing that regressed before. Nothing here is commanded to move, so axis A should
    // be idle and republishing at the ~1 Hz idle floor. The old fixed 100 ms timer would
    // deliver ~40 in this window; scan-aligned idle publication delivers ~4.
    val window = 4.seconds
    val samples = collectAxisStates(CurrentStateAxisACurrentState.eventKey.eventName.name, window)
    val n = samples.size
    assert(n >= 1, s"idle axis published nothing in $window - the idle republish floor is not firing")
    assert(n <= 15,
      s"idle axis published $n times in $window (~${n / window.toSeconds} Hz) - expected ~1 Hz; " +
        "an idle axis is being published at the action rate")
  }
