package csw.proto.galil.assembly

import java.net.InetAddress

import akka.actor
import akka.actor.Scheduler
import akka.stream.{ActorMaterializer, Materializer}
import akka.typed.ActorSystem
import akka.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.typed.testkit.TestKitSettings
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.framework.internal.wiring.{Container, FrameworkWiring}
import csw.messages.ccs.CommandIssue._
import csw.messages.ccs.commands.CommandResponse.{Accepted, Completed, Invalid}
import csw.messages.ccs.commands.{CommandName, ComponentRef, Setup}
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location.{ComponentId, ComponentType}
import csw.messages.params.generics.{Key, KeyType, Parameter}
import csw.messages.params.models.{Prefix, Units}
import csw.services.location.commons.ClusterSettings
import csw.services.location.scaladsl.{LocationService, LocationServiceFactory}
import csw.services.logging.scaladsl.LoggingSystemFactory
import org.scalatest.{BeforeAndAfterAll, Matchers}

import scala.async.Async._
import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext}

class GalilAssemblyHandlersTest extends org.scalatest.FunSuite with Matchers with BeforeAndAfterAll {
  implicit val seedActorSystem: actor.ActorSystem = ClusterSettings().onPort(3552).system
  private val containerActorSystem: actor.ActorSystem = ClusterSettings().joinLocal(3552).system

  implicit val typedSystem: ActorSystem[_] = seedActorSystem.toTyped
  implicit val testKitSettings: TestKitSettings = TestKitSettings(typedSystem)
  implicit val mat: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = typedSystem.executionContext
  implicit val timeout: Timeout = 5.seconds
  implicit val scheduler: Scheduler = typedSystem.scheduler
  private val locationService: LocationService = LocationServiceFactory.withSystem(seedActorSystem)

  private val loggingSystem = LoggingSystemFactory.start("GalilTest", "No Version", InetAddress.getLocalHost.getHostAddress, seedActorSystem)

  override protected def afterAll(): Unit = Await.result(seedActorSystem.terminate(), 5.seconds)

  private val wiring: FrameworkWiring = FrameworkWiring.make(containerActorSystem)
  Container.spawn(ConfigFactory.load("GalilAssemblyAndHcd.conf"), wiring)


  // resolve assembly running in jvm-3 and send setup command expecting immediate command completion response
  private val assemblyLocF = locationService.resolve(AkkaConnection(ComponentId("GalilAssembly", ComponentType.Assembly)), 5.seconds)
  private val assemblyComponent: ComponentRef = Await.result(assemblyLocF, 5.seconds).map(_.component).get

  test("should validate proper SingleFilterMove command") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Int] = KeyType.IntKey.make("target")

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Int] = k2.set(100).withUnits(Units.count)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None)
      .add(i1)
      .add(i2)

    val eventualResponse = async {
      val initialCommandResponse = await(assemblyComponent.submit(moveSetup))
      initialCommandResponse shouldBe an[Accepted]
      await(assemblyComponent.subscribe(moveSetup.runId))
    }

    val response = Await.result(eventualResponse, timeout.duration)

    response shouldBe a[Completed]
    response.runId shouldBe moveSetup.runId

  }

  test("should get invalid response for command with incorrect parameter name") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Int] = KeyType.IntKey.make("target")
    val k3: Key[Int] = KeyType.IntKey.make("foo")  // incorrectly named optional parameter

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Int] = k2.set(100).withUnits(Units.count)
    val i3: Parameter[Int] = k3.set(0)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None)
      .add(i1)
      .add(i2)
      .add(i3)


    val initialCommandResponse = Await.result(assemblyComponent.submit(moveSetup), 5.seconds)
    initialCommandResponse shouldBe an[Invalid]
    initialCommandResponse.asInstanceOf[Invalid].issue shouldBe an[OtherIssue]
  }

  test("should get invalid response for command with missing required parameter") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Int] = KeyType.IntKey.make("foo")  // incorrectly named optional parameter

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Int] = k2.set(100).withUnits(Units.count)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None)
      .add(i1)
      .add(i2)


    val initialCommandResponse = Await.result(assemblyComponent.submit(moveSetup), 5.seconds)
    initialCommandResponse shouldBe an[Invalid]
    initialCommandResponse.asInstanceOf[Invalid].issue shouldBe an[MissingKeyIssue]
  }


  test("should get invalid response for command with incorrect parameter units") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Int] = KeyType.IntKey.make("target")

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Int] = k2.set(100).withUnits(Units.hertz)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None)
      .add(i1)
      .add(i2)


    val initialCommandResponse = Await.result(assemblyComponent.submit(moveSetup), 5.seconds)
    initialCommandResponse shouldBe an[Invalid]
    initialCommandResponse.asInstanceOf[Invalid].issue shouldBe an[WrongUnitsIssue]
  }

  test("should get invalid response for command with incorrect parameter type") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Double] = KeyType.DoubleKey.make("target")

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Double] = k2.set(100).withUnits(Units.count)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None)
      .add(i1)
      .add(i2)


    val initialCommandResponse = Await.result(assemblyComponent.submit(moveSetup), 5.seconds)
    initialCommandResponse shouldBe an[Invalid]
    initialCommandResponse.asInstanceOf[Invalid].issue shouldBe an[WrongParameterTypeIssue]
  }

  test("should get invalid response for command with too few number of parameters") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")

    val i1: Parameter[Int] = k1.set(1)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None).add(i1)

    val initialCommandResponse = Await.result(assemblyComponent.submit(moveSetup), 5.seconds)
    initialCommandResponse shouldBe an[Invalid]
    initialCommandResponse.asInstanceOf[Invalid].issue shouldBe an[WrongNumberOfParametersIssue]
  }

  test("should get invalid response for command with too many number of parameters") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Int] = KeyType.IntKey.make("target")
    val k3: Key[Double] = KeyType.DoubleKey.make("speed")
    val k4: Key[Double] = KeyType.DoubleKey.make("foo")

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Int] = k2.set(100).withUnits(Units.count)
    val i3: Parameter[Double] = k3.set(200.2).withUnits(Units.millisecond)
    val i4: Parameter[Double] = k4.set(300.3).withUnits(Units.millisecond)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None)
      .add(i1)
      .add(i2)
      .add(i3)
      .add(i4)

    val initialCommandResponse = Await.result(assemblyComponent.submit(moveSetup), 5.seconds)
    initialCommandResponse shouldBe an[Invalid]
    initialCommandResponse.asInstanceOf[Invalid].issue shouldBe an[WrongNumberOfParametersIssue]
  }
  test("should get invalid response for invalid command") {

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Int] = KeyType.IntKey.make("target")

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Int] = k2.set(100).withUnits(Units.count)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("Foo"), None)
      .add(i1)
      .add(i2)


    val initialCommandResponse = Await.result(assemblyComponent.submit(moveSetup), 5.seconds)
    initialCommandResponse shouldBe an[Invalid]
    initialCommandResponse.asInstanceOf[Invalid].issue shouldBe an[UnsupportedCommandIssue]
  }


}