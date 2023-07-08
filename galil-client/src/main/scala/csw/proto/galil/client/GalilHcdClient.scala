package csw.proto.galil.client

import java.io.IOException

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import akka.util.{ByteString, Timeout}
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.HCD
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.scaladsl.LocationService
import csw.params.commands.CommandResponse.Error
import csw.params.commands.{CommandName, CommandResponse, Setup}
import csw.params.core.generics.{Key, KeyType}
import csw.params.core.models.{Id, ObsId}
import csw.prefix.models.Prefix
import csw.proto.galil.io.DataRecord

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

/**
 * A client for locating and communicating with the Galil HCD
 *
 * @param source          the client's prefix
 * @param locationService a reference to the location service
 */
case class GalilHcdClient(source: Prefix, locationService: LocationService)(implicit
    typedSystem: ActorSystem[SpawnProtocol.Command],
    ec: ExecutionContextExecutor
) {

  implicit val timeout: Timeout = Timeout(3.seconds)

  private val connection = AkkaConnection(ComponentId(Prefix("csw.galil.hcd.GalilHcd"), HCD))

  private val axisKey: Key[Char]        = KeyType.CharKey.make("axis")
  private val countsKey: Key[Int]       = KeyType.IntKey.make("counts")
  private val interpCountsKey: Key[Int] = KeyType.IntKey.make("interpCounts")
  private val brushlessModulusKey: Key[Int] =
    KeyType.IntKey.make("brushlessModulus")
  private val voltsKey: Key[Double] = KeyType.DoubleKey.make("volts")
  private val speedKey: Key[Int]    = KeyType.IntKey.make("speed")

  /**
   * Gets a reference to the running Galil HCD from the location service, if found.
   */
  private def getGalilHcd: Future[Option[CommandService]] = {
    locationService
      .resolve(connection, 30.seconds)
      .map(_.map(CommandServiceFactory.make(_)))
  }

  /**
   * Sends a getDataRecord message to the HCD and returns the response
   */
  def getDataRecord(obsId: Option[ObsId], axis: Option[Char] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val s = Setup(source, CommandName("getDataRecord"), obsId)
        // FIXME: There are still problems parsing result when an axis argument is passed
        val setup = if (axis.isDefined) s.add(axisKey.set(axis.get)) else s
        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a getDataRecord message to the HCD and returns a DataRecord object
   */
  def getDataRecordRaw(obsId: Option[ObsId], axis: Option[Char] = None): Future[DataRecord] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val s = Setup(source, CommandName("getDataRecordRaw"), obsId)
        // FIXME: There are still problems parsing result when an axis argument is passed
        val setup = if (axis.isDefined) s.add(axisKey.set(axis.get)) else s
        hcd.submitAndWait(setup).map {
          case CommandResponse.Completed(_, result) =>
            val bytes = result.get(DataRecord.key).get.head.values
            DataRecord(ByteString(bytes))
          case x =>
            throw new IOException(s"Unexpected result from getDataRecordRaw command: $x")
        }

      case None =>
        Future.failed(new IOException("Can't locate Galil HCD"))
    }
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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

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

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

}
