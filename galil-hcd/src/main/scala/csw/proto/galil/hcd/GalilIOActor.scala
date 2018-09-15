package csw.proto.galil.hcd

import java.io.IOException

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.util.ByteString
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}
import akka.actor.typed.scaladsl.{ActorContext, MutableBehavior}
import csw.command.CommandResponseManager
import csw.logging.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.CompletedWithResult
import csw.params.commands.Result
import csw.params.core.models.{Id, ObsId, Prefix}

/**
  * Worker actor that handles the Galil I/O
  */
private[hcd] object GalilIOActor {
  def behavior(galilConfig: GalilConfig, commandResponseManager: CommandResponseManager,
               adapter: CSWDeviceAdapter, loggerFactory: LoggerFactory): Behavior[GalilCommandMessage] =
    Behaviors.setup(ctx ⇒ GalilIOActor(ctx, galilConfig, commandResponseManager, adapter, loggerFactory))
}

private[hcd] case class GalilIOActor(ctx: ActorContext[GalilCommandMessage],
                                     galilConfig: GalilConfig,
                                     commandResponseManager: CommandResponseManager,
                                     adapter: CSWDeviceAdapter,
                                     loggerFactory: LoggerFactory)
  extends MutableBehavior[GalilCommandMessage] {

  private val log = loggerFactory.getLogger

  private val galilIo = connectToGalil()
  verifyGalil()

  // Connect to Galikl device and throw error if that doesn't work
  private def connectToGalil(): GalilIo = {
    try {
      GalilIoTcp(galilConfig.host, galilConfig.port)
    } catch {
      case ex: Exception =>
        log.error(s"Failed to connect to Galil device at ${galilConfig.host}:${galilConfig.port}")
        throw ex
    }
  }

  // Check that there is a Galil device on the other end of the socket (Is there good Galil command to use here?)
  private def verifyGalil(): Unit = {
    val s = galilSend("")
    if (s.nonEmpty)
      throw new IOException(s"Unexpected response to empty galil command: '$s': " +
        s"Check if Galil device is really located at ${galilConfig.host}:${galilConfig.port}")
  }

  override def onMessage(msg: GalilCommandMessage): Behavior[GalilCommandMessage] = {
    msg match {
      case x: GalilCommandMessage => processCommand(x)
      case _ => log.error(s"unhandled message in GalilIOActor onMessage: $msg")
    }
    this
  }

  private def processCommand(message: GalilCommandMessage): Unit = message match {
    case GalilCommand(commandString) =>
      // TODO
      log.debug(s"doing command: $commandString")

    case GalilRequest(commandString, prefix, runId, maybeObsId, commandKey) =>
      log.debug(s"doing command: $commandString")
      if (commandString.startsWith("QR")) {
        val response = galilIo.send(commandString)
        val bs = response.head._2
        log.debug(s"Data Record size: ${bs.size})")
        if (commandKey.name.equals("getDataRecord")) {
          // parse the data record
          val dr = DataRecord(bs)
          log.debug(s"Data Record: $dr")
          handleDataRecordResponse(dr, prefix, runId, maybeObsId, commandKey)
        } else {
          // return a paramset with the raw data record bytes
          handleDataRecordRawResponse(bs, prefix, runId, maybeObsId, commandKey)
        }
      } else {
        val response = galilSend(commandString)
        handleGalilResponse(response, prefix, runId, maybeObsId, commandKey)
      }

    case _ => log.debug("unhanded GalilCommandMessage")
  }

  private def handleDataRecordResponse(dr: DataRecord, prefix: Prefix, runId: Id, maybeObsId: Option[ObsId],
                                  cmdMapEntry: CommandMapEntry): Unit = {
    log.debug(s"handleDataRecordResponse $dr")
    val returnResponse = DataRecord.makeCommandResponse(prefix, runId, maybeObsId, dr)
    commandResponseManager.addOrUpdateCommand(returnResponse.runId, returnResponse)
  }

  private def handleDataRecordRawResponse(bs: ByteString, prefix: Prefix, runId: Id, maybeObsId: Option[ObsId],
                                  cmdMapEntry: CommandMapEntry): Unit = {
    val returnResponse = CompletedWithResult(runId, Result(prefix, Set(DataRecord.key.set(bs.toByteBuffer.array()))))
    commandResponseManager.addOrUpdateCommand(returnResponse.runId, returnResponse)
  }

  private def handleGalilResponse(response: String, prefix: Prefix, runId: Id, maybeObsId: Option[ObsId],
                                  cmdMapEntry: CommandMapEntry): Unit = {
    log.debug(s"handleGalilResponse $response")
    val returnResponse = adapter.makeResponse(prefix, runId, maybeObsId, cmdMapEntry, response)
    commandResponseManager.addOrUpdateCommand(returnResponse.runId, returnResponse)
  }

  private def galilSend(cmd: String): String = {
    log.debug(s"Sending '$cmd' to Galil")
    val responses = galilIo.send(cmd)
    if (responses.lengthCompare(1) != 0)
      throw new RuntimeException(s"Received ${responses.size} responses to Galil $cmd")
    val resp = responses.head._2.utf8String
    log.debug(s"Response from Galil: $resp")
    resp
  }
}
