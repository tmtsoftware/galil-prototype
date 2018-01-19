package csw.proto.galil.assembly

import akka.actor
import akka.actor.Scheduler
import akka.stream.{ActorMaterializer, Materializer}
import akka.typed.ActorSystem
import akka.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.typed.testkit.TestKitSettings
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd
import csw.framework.internal.wiring.{Container, FrameworkWiring, Standalone}
import csw.messages.ccs.commands.CommandResponse.{Accepted, Completed}
import csw.messages.ccs.commands.{CommandName, ComponentRef, Setup}
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location.{ComponentId, ComponentType}
import csw.messages.params.generics.{Key, KeyType, Parameter}
import csw.messages.params.models.Prefix
import csw.services.location.commons.ClusterSettings
import csw.services.location.scaladsl.{LocationService, LocationServiceFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers}

import scala.async.Async._
import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext}

class GalilAssemblyHandlersTest extends org.scalatest.FunSuite with Matchers with BeforeAndAfterAll {
  implicit val seedActorSystem: actor.ActorSystem = ClusterSettings().onPort(3552).system
  private val containerActorSystem: actor.ActorSystem = ClusterSettings().joinLocal(3552).system

  implicit val typedSystem: ActorSystem[_]        = seedActorSystem.toTyped
  implicit val testKitSettings: TestKitSettings   = TestKitSettings(typedSystem)
  implicit val mat: Materializer                  = ActorMaterializer()
  implicit val ec: ExecutionContext        = typedSystem.executionContext
  implicit val timeout: Timeout            = 5.seconds
  implicit val scheduler: Scheduler        = typedSystem.scheduler
  private val locationService: LocationService    = LocationServiceFactory.withSystem(seedActorSystem)

  override protected def afterAll(): Unit = Await.result(seedActorSystem.terminate(), 5.seconds)

  test("should validate proper SingleFilterMove command") {
    val wiring: FrameworkWiring = FrameworkWiring.make(containerActorSystem)
    Container.spawn(ConfigFactory.load("GalilAssemblyAndHcd.conf"), wiring)

    // resolve assembly running in jvm-3 and send setup command expecting immediate command completion response
    val assemblyLocF = locationService.resolve(AkkaConnection(ComponentId("GalilAssembly", ComponentType.Assembly)), 5.seconds)
    val assemblyComponent: ComponentRef  = Await.result(assemblyLocF, 10.seconds).map(_.component).get

    val k1: Key[Int] = KeyType.IntKey.make("wheelNum")
    val k2: Key[Int] = KeyType.IntKey.make("test")

    val i1: Parameter[Int] = k1.set(1)
    val i2: Parameter[Int] = k2.set(100)

    val moveSetup = Setup(Prefix("csw.prototype.test"), CommandName("SingleFilterMove"), None).add(i1).add(i2)

    val eventualResponse = async {
      val initialCommandResponse = await(assemblyComponent.submit(moveSetup))
      initialCommandResponse shouldBe an[Accepted]
      await(assemblyComponent.subscribe(moveSetup.runId))
    }

    val response = Await.result(eventualResponse, timeout.duration)

    response shouldBe a[Completed]
    response.runId shouldBe moveSetup.runId

  }
}
