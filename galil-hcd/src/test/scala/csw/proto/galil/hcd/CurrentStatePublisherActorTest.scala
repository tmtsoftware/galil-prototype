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
    
    // Test active axes (A and B per default GalilHcdConfig.conf)
    // Inactive axes (C-H) won't have CurrentState published
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