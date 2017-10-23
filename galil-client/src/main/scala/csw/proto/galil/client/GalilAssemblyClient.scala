package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.stream.ActorMaterializer
import akka.typed.scaladsl.adapter._
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.{ActorRef, Behavior}
import csw.messages.CommandMessage.Submit
import csw.messages.SupervisorExternalMessage
import csw.messages.ccs.commands.Setup
import csw.messages.location.ComponentType.Assembly
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location._
import csw.messages.params.generics.KeyType
import csw.messages.params.models.Prefix
import csw.messages.params.models.Units.degree
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.scaladsl.LocationServiceFactory
import csw.services.logging.scaladsl.{CommonComponentLogger, LoggingSystemFactory}

object GalilAssemblyClientLogger extends CommonComponentLogger("GalilAssemblyClient")

// A client to test locating and communicating with the Galil assembly
object GalilAssemblyClient extends App with GalilAssemblyClientLogger.Simple {

  private val system: ActorSystem = ClusterAwareSettings.system
  implicit def actorRefFactory: ActorRefFactory = system
  private val locationService = LocationServiceFactory.withSystem(system)
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilAssemblyClientApp", "0.1", host, system)
  implicit val mat: ActorMaterializer = ActorMaterializer()
  log.info("Starting GalilAssemblyClient")
  system.spawn(initialBehavior, "GalilAssemblyClient")

  def initialBehavior: Behavior[TrackingEvent] =
    Actor.deferred { ctx =>
      val connection = AkkaConnection(ComponentId("GalilAssembly", Assembly))
      locationService.subscribe(connection, { loc =>
        ctx.self ! loc
      })
      subscriberBehavior
    }

  def subscriberBehavior: Behavior[TrackingEvent] = {
    Actor.immutable[TrackingEvent] { (ctx, msg) =>
      msg match {
        case LocationUpdated(loc) =>
          log.info(s"LocationUpdated: $loc")
          interact(ctx, loc.asInstanceOf[AkkaLocation].typedRef[SupervisorExternalMessage])
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

  private def interact(ctx: ActorContext[TrackingEvent], assembly: ActorRef[SupervisorExternalMessage]): Unit = {
    val k1 = KeyType.IntKey.make("encoder")
    val k2 = KeyType.StringKey.make("filter")
    val i1 = k1.set(22, 33, 44)
    val i2 = k2.set("a", "b", "c").withUnits(degree)
    val setup = Setup("Obs001", Prefix("wfos.blue.filter")).add(i1).add(i2)
    assembly ! Submit(setup, replyTo = ctx.spawnAnonymous(Actor.ignore))
  }
}

