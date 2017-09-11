package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorRefFactory
import akka.stream.ActorMaterializer
import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.{Actor, ActorContext}
import csw.common.framework.models.CommandMessage.Submit
import csw.param.commands.Setup
import csw.param.generics.KeyType
import csw.param.models.Prefix
import csw.services.location.models._
import csw.services.location.scaladsl.{ActorSystemFactory, LocationServiceFactory}
import csw.services.logging.scaladsl.{GenericLogger, LoggingSystemFactory}
import csw.units.Units.degree
import akka.typed.scaladsl.adapter._
import csw.common.framework.models.ComponentMessage
import csw.services.location.models.ComponentType.Assembly
import csw.services.location.models.Connection.AkkaConnection

// A client to test locating and communicating with the Galil assembly
object GalilAssemblyClient extends App with GenericLogger.Simple {

  private val system = ActorSystemFactory.remote
  implicit def actorRefFactory: ActorRefFactory = system
  private val locationService = LocationServiceFactory.make()
  private val host = InetAddress.getLocalHost.getHostName
  private val loggingSystem = LoggingSystemFactory.start("TestServiceClientApp", "0.1", host, system)
  implicit val mat: ActorMaterializer = ActorMaterializer()
  log.info("Starting GalilAssemblyClient")
  system.spawn(initialBehavior, "GalilAssemblyClient")

  private def initialBehavior: Behavior[TrackingEvent] = {
    Actor.immutable[TrackingEvent] { (ctx, msg) =>
      val connection = AkkaConnection(ComponentId("GalilAssembly", Assembly))
      locationService.subscribe(connection, ctx.self ! _)
      msg match {
        case LocationUpdated(loc) =>
          log.info(s"LocationUpdated: $loc")
          interact(ctx, loc.asInstanceOf[AkkaLocation].typedRef)
        case LocationRemoved(loc) =>
          log.info(s"LocationRemoved: $loc")
      }
      Actor.same
    } onSignal {
      case (ctx, x) =>
        log.info(s"${ctx.self} received signal $x")
        Actor.stopped
    }
  }

  private def interact(ctx: ActorContext[TrackingEvent], assembly: ActorRef[ComponentMessage]): Unit = {
    val k1 = KeyType.IntKey.make("encoder")
    val k2 = KeyType.StringKey.make("filter")
    val i1 = k1.set(22, 33, 44)
    val i2 = k2.set("a", "b", "c").withUnits(degree)
    val setup = Setup("Obs001", Prefix("wfos.blue.filter")).add(i1).add(i2)
    assembly ! Submit(setup, replyTo = ctx.spawnAnonymous(Actor.ignore))
  }
}
