package csw.proto.galil.hcd

import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.{Actor, ActorContext}
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.hcd.GalilResponseMessage.GalilResponse
import csw.services.logging.scaladsl.ComponentLogger

object GalilIOActor {
  def behavior(galilConfig: GalilConfig, replyTo: Option[ActorRef[GalilResponseMessage]]): Behavior[GalilCommandMessage] =
    Actor.mutable[GalilCommandMessage](ctx ⇒ new GalilIOActor(ctx, galilConfig: GalilConfig, replyTo)).narrow


}

class GalilIOActor(ctx: ActorContext[GalilCommandMessage],
                   galilConfig: GalilConfig,
                   replyTo: Option[ActorRef[GalilResponseMessage]]
                  ) extends Actor.MutableBehavior[GalilCommandMessage]
  with ComponentLogger.Simple {
  override def componentName(): String = "GalilIOActor"

  override def onMessage(msg: GalilCommandMessage): Behavior[GalilCommandMessage] = {
    msg match {
      case (x: GalilCommandMessage) => processCommand(x)
      case _ => log.error(s"unhandled message in GalilIOActor onMessage: $msg")
    }
    this
  }

  def processCommand(message: GalilCommandMessage): Unit = message match {
    case GalilCommand(commandString) =>
      log.debug(s"doing command: $commandString")

    case GalilRequest(commandString, replyTo) =>
      log.debug(s"doing command: $commandString")
      replyTo ! GalilResponse("test response")

    case _ => log.debug("unhanded GalilCommandMessage")
  }


}
