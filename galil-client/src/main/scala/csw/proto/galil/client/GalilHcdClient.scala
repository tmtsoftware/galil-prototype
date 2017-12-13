package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.{ActorRefFactory, ActorSystem, Scheduler}
import akka.stream.ActorMaterializer
import akka.typed.scaladsl.adapter._
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.Behavior
import akka.util.Timeout
import csw.messages.ccs.commands.{CommandName, Setup, WrappedComponent}
import csw.messages.location.ComponentType.HCD
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location._
import csw.messages.params.generics.{Key, KeyType}
import csw.messages.params.models.Prefix
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.scaladsl.LocationServiceFactory
import csw.services.logging.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import scala.concurrent.duration._

// A client to test locating and communicating with the Galil HCD
object GalilHcdClient extends App {

  private val system: ActorSystem = ClusterAwareSettings.system
  implicit def actorRefFactory: ActorRefFactory = system
  private val locationService = LocationServiceFactory.withSystem(system)
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, system)
  implicit val mat: ActorMaterializer = ActorMaterializer()
  private val log = GenericLoggerFactory.getLogger
  log.info("Starting GalilHcdClient")
  system.spawn(initialBehavior, "GalilHcdClient")

  // The initial behavior is to look up the HCD with the location service
  private def initialBehavior: Behavior[TrackingEvent] =
    Actor.deferred { ctx =>
      val connection = AkkaConnection(ComponentId("GalilHcd", HCD))
      locationService.subscribe(connection, { loc =>
        ctx.self ! loc
      })
      subscriberBehavior
    }

  // Behavior while waiting for the HCD location
  private def subscriberBehavior: Behavior[TrackingEvent] = {
    Actor.immutable[TrackingEvent] { (ctx, msg) =>
      msg match {
        case LocationUpdated(loc) =>
          log.info(s"LocationUpdated: $loc")
          interact(ctx, loc.asInstanceOf[AkkaLocation].component())
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

  // Sends a message to the HCD (and ignores any reply, for now)
  private def interact(ctx: ActorContext[TrackingEvent], hcd: WrappedComponent): Unit = {
    implicit val timeout: Timeout = Timeout(3.seconds)
    implicit val scheduler: Scheduler = ctx.system.scheduler
    // XXX FIXME Dummy value
    val prefix = Prefix("wfos.blue.filter")
    val maybeObsId = None

    val commandKey: Key[String] = KeyType.StringKey.make("command")
    val axisKey: Key[Char] = KeyType.CharKey.make("axis")
    val countsKey: Key[Int] = KeyType.IntKey.make("counts")

    val setup = Setup(prefix, CommandName("filter"), maybeObsId)
      .add(commandKey.set("setRelTarget"))
      .add(axisKey.set('A'))
      .add(countsKey.set(2))

    hcd.submit(setup)
  }
}

