package csw.proto.galil.client

import java.io.IOException

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import org.apache.pekko.util.{ByteString, Timeout}
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.HCD
import csw.location.api.models.Connection.PekkoConnection
import csw.location.api.scaladsl.LocationService
import csw.params.commands.CommandResponse.Error
import csw.params.commands.{CommandName, CommandResponse, Setup}
import csw.params.core.generics.{Key, KeyType}
import csw.params.core.models.{Id, ObsId}
import csw.prefix.models.Prefix
import csw.proto.galil.io.DataRecord

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * A client for locating and communicating with the Galil HCD
 * Updated for new embedded program execution interface
 *
 * @param source          the client's prefix
 * @param locationService a reference to the location service
 */
case class GalilHcdClient(source: Prefix, locationService: LocationService)(implicit
    typedSystem: ActorSystem[SpawnProtocol.Command],
    ec: ExecutionContext
) {

  implicit val timeout: Timeout = Timeout(10.seconds)

  private val connection = PekkoConnection(ComponentId(Prefix("aps.ICS.HCD.GalilMotion"), HCD))

  // Parameter keys for new interface
  private val labelKey: Key[String]         = KeyType.StringKey.make("label")
  private val threadKey: Key[Int]           = KeyType.IntKey.make("thread")
  private val addressKey: Key[Int]          = KeyType.IntKey.make("address")
  private val channelKey: Key[Int]          = KeyType.IntKey.make("channel")
  private val valueKey: Key[Double]         = KeyType.DoubleKey.make("value")
  private val commandStringKey: Key[String] = KeyType.StringKey.make("commandString")
  private val filenameKey: Key[String]      = KeyType.StringKey.make("filename")

  /**
   * Gets a reference to the running Galil HCD from the location service, if found.
   */
  private def getGalilHcd: Future[Option[CommandService]] = {
    locationService
      .resolve(connection, 30.seconds)
      .map(_.map(CommandServiceFactory.make(_)))
  }

  // ===== Data Record Commands =====

  /**
   * Sends a getDataRecord command to the HCD and returns the response
   * The DataRecord will be converted to CSW parameters
   */
  def getDataRecord(obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("getDataRecord"), obsId)
        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Sends a getDataRecordRaw command to the HCD and returns a DataRecord object
   */
  def getDataRecordRaw(obsId: Option[ObsId] = None): Future[DataRecord] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("getDataRecordRaw"), obsId)
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

  // ===== Program Execution Commands (NEW) =====

  /**
   * Execute an embedded program on a specified thread
   * 
   * @param label  Program label (e.g., "MoveA", "POSA")
   * @param thread Thread number (1-7)
   * @param obsId  Optional observation ID
   */
  def executeProgram(label: String, thread: Int, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("executeProgram"), obsId)
          .add(labelKey.set(label))
          .add(threadKey.set(thread))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Halt execution of a program on specified thread
   * 
   * @param thread Thread number to halt (1-7)
   * @param obsId  Optional observation ID
   */
  def haltExecution(thread: Int, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("haltExecution"), obsId)
          .add(threadKey.set(thread))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  // ===== Digital I/O Commands (NEW) =====

  /**
   * Set a digital output bit
   * 
   * @param address Bit address
   * @param obsId   Optional observation ID
   */
  def setBit(address: Int, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("setBit"), obsId)
          .add(addressKey.set(address))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Clear a digital output bit
   * 
   * @param address Bit address
   * @param obsId   Optional observation ID
   */
  def clearBit(address: Int, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("clearBit"), obsId)
          .add(addressKey.set(address))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  // ===== Analog I/O Commands (NEW) =====

  /**
   * Read an analog input channel
   * 
   * @param channel AI channel number
   * @param obsId   Optional observation ID
   */
  def readAnalogInput(channel: Int, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("readAnalogInput"), obsId)
          .add(channelKey.set(channel))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Write to an analog output channel
   * 
   * @param channel AO channel number
   * @param value   Value to write (voltage)
   * @param obsId   Optional observation ID
   */
  def writeAnalogOutput(channel: Int, value: Double, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("writeAnalogOutput"), obsId)
          .add(channelKey.set(channel))
          .add(valueKey.set(value))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  // ===== Utility Commands (NEW) =====

  /**
   * Upload DMC program file to controller
   * Reads from programs directory (resources or runtime)
   * 
   * @param filename DMC file to upload (e.g., "protoHCD_lab.dmc")
   * @param obsId    Optional observation ID
   */
  def uploadProgram(filename: String, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("uploadProgram"), obsId)
          .add(filenameKey.set(filename))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Download all programs from controller to file
   * Saves to runtime programs directory
   * 
   * @param filename Output file name (e.g., "current.dmc" or "backup_20241217.dmc")
   * @param obsId    Optional observation ID
   */
  def downloadProgram(filename: String, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("downloadProgram"), obsId)
          .add(filenameKey.set(filename))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  /**
   * Send an arbitrary DMC command string to the controller
   * This is an escape hatch for commands not explicitly defined
   * Use this for commands that don't return values (e.g., "HX", "SB 1")
   * 
   * @param commandString The DMC command to send
   * @param obsId         Optional observation ID
   */
  def sendCommand(commandString: String, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("sendCommand"), obsId)
          .add(commandStringKey.set(commandString))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }
  
  /**
   * Send an arbitrary DMC command and capture the return value
   * Use this for commands that return values (e.g., "MG version", "MG _TPA")
   * The response is returned in the 'response' parameter of the Result
   * 
   * @param commandString The DMC command to send
   * @param obsId         Optional observation ID
   */
  def sendCommandRV(commandString: String, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    getGalilHcd.flatMap {
      case Some(hcd) =>
        val setup = Setup(source, CommandName("sendCommandRV"), obsId)
          .add(commandStringKey.set(commandString))

        hcd.submitAndWait(setup)

      case None =>
        Future.successful(Error(Id(), "Can't locate Galil HCD"))
    }
  }

  // ===== Convenience Methods =====

  /**
   * Set a Galil variable (convenience method using sendCommand)
   * This doesn't return the value, just confirms success
   * 
   * @param variable Variable name (e.g., "posn[0]", "speed")
   * @param value    Value to set
   * @param obsId    Optional observation ID
   */
  def setVariable(variable: String, value: Double, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    sendCommand(s"$variable=$value", obsId)
  }

  /**
   * Get a Galil variable value (convenience method using sendCommandRV)
   * Returns the value in the 'response' parameter of the Result
   * 
   * @param variable Variable name (e.g., "version", "posn[0]", "_TDA")
   * @param obsId    Optional observation ID
   */
  def getVariable(variable: String, obsId: Option[ObsId] = None): Future[CommandResponse] = {
    sendCommandRV(s"MG $variable", obsId)
  }
}