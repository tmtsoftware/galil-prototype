package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.stream.ActorMaterializer
import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.{Actor, ActorContext}
import csw.services.location.scaladsl.LocationServiceFactory
import csw.services.logging.scaladsl.{ComponentLogger, LoggingSystemFactory}
import akka.typed.scaladsl.adapter._
import csw.messages.CommandMessage.Submit
import csw.messages.{ComponentMessage, SupervisorExternalMessage}
import csw.messages.ccs.commands.Setup
import csw.messages.location.ComponentType.HCD
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location._
import csw.messages.params.generics.KeyType
import csw.messages.params.models.Prefix
import csw.messages.params.models.Units.degree
import csw.services.location.commons.ClusterAwareSettings

// A client to test locating and communicating with the Galil HCD
object GalilHcdClient extends App with ComponentLogger.Simple {

  override def componentName(): String = "GalilHcdClient"
  private val system: ActorSystem = ClusterAwareSettings.system
  implicit def actorRefFactory: ActorRefFactory = system
  private val locationService = LocationServiceFactory.withSystem(system)
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, system)
  implicit val mat: ActorMaterializer = ActorMaterializer()
  log.info("Starting GalilHcdClient")
  system.spawn(initialBehavior, "GalilHcdClient")

  def initialBehavior: Behavior[TrackingEvent] =
    Actor.deferred { ctx =>
      val connection = AkkaConnection(ComponentId("GalilHcd", HCD))
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

  private def interact(ctx: ActorContext[TrackingEvent], hcd: ActorRef[SupervisorExternalMessage]): Unit = {
    val axis = KeyType.CharKey.make("axis")
    val axisItem = axis.set('A')
    val setup = Setup("Obs001", Prefix("galil.command.getRelTarget")).add(axisItem)
    hcd ! Submit(setup, replyTo = ctx.spawnAnonymous(Actor.ignore))
  }
}

