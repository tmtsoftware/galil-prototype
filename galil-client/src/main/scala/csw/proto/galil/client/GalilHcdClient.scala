package csw.proto.galil.client

import akka.actor.{ActorRefFactory, ActorSystem, Scheduler}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import csw.messages.ccs.commands.CommandResponse.Error
import csw.messages.ccs.commands.{CommandName, CommandResponse, ComponentRef, Setup}
import csw.messages.location.ComponentType.HCD
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location._
import csw.messages.params.generics.{Key, KeyType}
import csw.messages.params.models.{ObsId, Prefix, RunId}
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.scaladsl.LocationServiceFactory
import csw.services.logging.scaladsl.LoggingSystemFactory

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * A client for locating and communicating with the Galil HCD
  *
  * @param source the client's prefix
  * @param system optional ActorSystem (must be created by ClusterAwareSettings.system, pass in existing system, if you have one)
  */
case class GalilHcdClient(source: Prefix, system: ActorSystem = ClusterAwareSettings.system) {

  import system._

  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit val scheduler: Scheduler = system.scheduler
  implicit def actorRefFactory: ActorRefFactory = system
  implicit val mat: ActorMaterializer = ActorMaterializer()

  private val locationService = LocationServiceFactory.withSystem(system)
  private val connection = AkkaConnection(ComponentId("GalilHcd", HCD))

  private val axisKey: Key[Char] = KeyType.CharKey.make("axis")
  private val countsKey: Key[Int] = KeyType.IntKey.make("counts")

  /**
    * Gets a reference to the running Galil HCD from the location service, if found.
    */
  private def getGalilHcd: Future[Option[ComponentRef]] = {
    locationService.resolve(connection, 30.seconds).map(_.map(_.component))
  }

  /**
    * Sends a setRelTarget message to the HCD and returns the response
    */
  def setRelTarget(obsId: Option[ObsId], axis: Char, count: Int): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setRelTarget"), obsId)
          .add(axisKey.set(axis))
          .add(countsKey.set(count))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(RunId(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a getRelTarget message to the HCD and returns the response
    */
  def getRelTarget(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("getRelTarget"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(RunId(), "Can't locate Galil HCD"))
    }
  }
}

