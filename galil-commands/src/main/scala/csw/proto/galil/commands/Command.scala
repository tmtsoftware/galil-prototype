package csw.proto.galil.commands

import akka.typed.ActorRef
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.{Actor, ActorContext}
import csw.messages.ccs.commands.{CommandResponse, CommandValidationResponse, Setup}
import csw.messages.{CommandMessage, ComponentMessage, SupervisorExternalMessage}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

abstract class Command(ctx: ActorContext[ComponentMessage], setup: Setup, component: ActorRef[SupervisorExternalMessage]) {

  val commandRunnerActor =  Actor.immutable[CommandResponse] { (_, msg) =>
    msg match {
      case x: CommandValidationResponse =>
    }
    Actor.same
  }

  val commandRunner = ctx.spawnAnonymous(commandRunnerActor)


  def submit(): Future[CommandResponse] = {
    component ? (CommandMessage.Submit(setup, _))
  }

  def oneway(): Future[CommandResponse] = {
    component ? (CommandMessage.Oneway(setup, _))
  }

  def submitAndWait(timeout: Duration): CommandResponse = {
    val future = submit()
    Await.result(future, timeout)
  }



}
