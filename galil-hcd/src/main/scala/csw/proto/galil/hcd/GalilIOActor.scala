package csw.proto.galil.hcd

import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.{Actor, ActorContext}
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.hcd.GalilResponseMessage.GalilResponse
import csw.proto.galil.io.GalilIoTcp
import csw.services.logging.scaladsl.LibraryLogger

object GalilIoLogger extends LibraryLogger("GalilHcd")

object GalilIOActor {
  def behavior(galilConfig: GalilConfig, replyTo: Option[ActorRef[GalilResponseMessage]],
               componentName: String): Behavior[GalilCommandMessage] =
    Actor.mutable(ctx ⇒ GalilIOActor(ctx, galilConfig, replyTo, componentName))
}

case class GalilIOActor(ctx: ActorContext[GalilCommandMessage],
                   galilConfig: GalilConfig,
                   replyTo: Option[ActorRef[GalilResponseMessage]],
                   componentName: String)
  extends GalilIoLogger.MutableActor[GalilCommandMessage](ctx) {

  val galilIo = GalilIoTcp() // TODO: Add configuration: Otherwise using default params: "127.0.0.1", 8888

  override def onMessage(msg: GalilCommandMessage): Behavior[GalilCommandMessage] = {
    msg match {
      case (x: GalilCommandMessage) => processCommand(x)
      case _ => log.error(s"unhandled message in GalilIOActor onMessage: $msg")
    }
    this
  }

  def processCommand(message: GalilCommandMessage): Unit = message match {
    case GalilCommand(commandString) =>
      // TODO
      log.debug(s"doing command: $commandString")

    case GalilRequest(commandString, prefix, cmdInfo, commandKey, client) =>
      log.debug(s"doing command: $commandString")
      val response = galilSend(commandString)
      // TODO handle error
      replyTo.foreach(_ ! GalilResponse(response, prefix, cmdInfo, commandKey, client))

    case _ => log.debug("unhanded GalilCommandMessage")
  }

  def galilSend(cmd: String): String = {
    val responses = galilIo.send(cmd)
    if (responses.size != 1)
      throw new RuntimeException(s"Received ${responses.size} responses to Galil $cmd")
    responses.head._2.utf8String
  }

}
