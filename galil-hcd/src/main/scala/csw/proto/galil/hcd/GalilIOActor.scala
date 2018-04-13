package csw.proto.galil.hcd

import java.io.IOException

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import csw.messages.params.models.{Id, ObsId, Prefix}
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}
import csw.services.command.scaladsl.CommandResponseManager
import csw.services.logging.scaladsl.LoggerFactory

/**
  *
  */
private[hcd] object GalilIOActor {
  def behavior(galilConfig: GalilConfig, commandResponseManager: CommandResponseManager,
               adapter: CSWDeviceAdapter, loggerFactory: LoggerFactory): Behavior[GalilCommandMessage] =
    Behaviors.mutable(ctx ⇒ GalilIOActor(ctx, galilConfig, commandResponseManager, adapter, loggerFactory))

  // Internal class used to build command response
  case class GalilResponse(response: String, prefix: Prefix, runId: Id, maybeObsId: Option[ObsId],
                           cmdMapEntry: CommandMapEntry)

}

private[hcd] case class GalilIOActor(ctx: ActorContext[GalilCommandMessage],
                        galilConfig: GalilConfig,
                        commandResponseManager: CommandResponseManager,
                        adapter: CSWDeviceAdapter,
                        loggerFactory: LoggerFactory)
  extends Behaviors.MutableBehavior[GalilCommandMessage] {

  import GalilIOActor._

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
      case (x: GalilCommandMessage) => processCommand(x)
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
      // TODO handle error
      if (commandString == "QR") {
        val response = galilIo.send("QR")
        val bs = response.head._2
        val dr = DataRecord(bs)
        log.debug(s"Data Record (size: ${bs.size}): $dr")
        // XXX TODO: What is the response for QR? Or is it only used internally?
        handleGalilResponse(GalilResponse("", prefix, runId, maybeObsId, commandKey))
      } else {
        val response = galilSend(commandString)
        handleGalilResponse(GalilResponse(response, prefix, runId, maybeObsId, commandKey))
      }

    case _ => log.debug("unhanded GalilCommandMessage")
  }

  private def handleGalilResponse(galilResponseMessage: GalilResponse): Unit = {
    log.debug(s"handleGalilResponse $galilResponseMessage")
    galilResponseMessage match {
      case GalilResponse(response, prefix, runId, maybeObsId, cmdMapEntry) =>
        val returnResponse = adapter.makeResponse(prefix, runId, maybeObsId, cmdMapEntry, response)
        commandResponseManager.addOrUpdateCommand(returnResponse.runId, returnResponse)
    }
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
