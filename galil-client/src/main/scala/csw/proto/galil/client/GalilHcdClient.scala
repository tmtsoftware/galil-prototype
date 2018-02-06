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
import csw.messages.params.models.{Id, ObsId, Prefix}
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
  private val interpCountsKey: Key[Int] = KeyType.IntKey.make("interpCounts")
  private val brushlessModulusKey: Key[Int] = KeyType.IntKey.make("brushlessModulus")
  private val voltsKey: Key[Double] = KeyType.DoubleKey.make("volts")
  private val speedKey: Key[Int] = KeyType.IntKey.make("speed")
  
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
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
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
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }
  
  /**
    * Sends a setAbsTarget message to the HCD and returns the response
    */
  def setAbsTarget(obsId: Option[ObsId], axis: Char, count: Int): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setAbsTarget"), obsId)
          .add(axisKey.set(axis))
          .add(countsKey.set(count))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a setBrushlessAxis message to the HCD and returns the response
    */
  def setBrushlessAxis(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setBrushlessAxis"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a setAnalogFeedbackSelect message to the HCD and returns the response
    */
  def setAnalogFeedbackSelect(obsId: Option[ObsId], axis: Char, interpCounts: Int): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setAnalogFeedbackSelect"), obsId)
          .add(axisKey.set(axis))
          .add(interpCountsKey.set(interpCounts))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }
 
  /**
    * Sends a setBrushlessModulus message to the HCD and returns the response
    */
  def setBrushlessModulus(obsId: Option[ObsId], axis: Char, brushlessModulus: Int): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setBrushlessModulus"), obsId)
          .add(axisKey.set(axis))
          .add(brushlessModulusKey.set(brushlessModulus))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a brushlessZero message to the HCD and returns the response
    */
  def brushlessZero(obsId: Option[ObsId], axis: Char, volts: Double): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("brushlessZero"), obsId)
          .add(axisKey.set(axis))
          .add(voltsKey.set(volts))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a motorOn message to the HCD and returns the response
    */
  def motorOn(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("motorOn"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a motorOff message to the HCD and returns the response
    */
  def motorOff(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("motorOff"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a setHomingMode message to the HCD and returns the response
    */
  def setHomingMode(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setHomingMode"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a beginMotion message to the HCD and returns the response
    */
  def beginMotion(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("beginMotion"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a setJogSpeed message to the HCD and returns the response
    */
  def setJogSpeed(obsId: Option[ObsId], axis: Char, speed: Int): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setJogSpeed"), obsId)
          .add(axisKey.set(axis))
          .add(speedKey.set(speed))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
    * Sends a setFindIndexMode message to the HCD and returns the response
    */
  def setFindIndexMode(obsId: Option[ObsId], axis: Char): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setFindIndexMode"), obsId)
          .add(axisKey.set(axis))

        hcd.submitAndSubscribe(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }












  
  
}

