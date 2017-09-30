package csw.proto.galil.hcd

import akka.typed.{ActorRef, ActorSystem, Behavior}
import akka.typed.scaladsl.Actor
import csw.param.commands.Setup
import csw.param.generics.KeyType
import csw.param.messages.CommandMessage.Submit
import csw.param.messages.SupervisorMessage
import csw.param.models.Prefix
import csw.units.Units.degree

// Temporary dummy HCD client to test sending the HCD a Submit message
object DummyHcdClient {

  private def behavior(supervisor: ActorRef[SupervisorMessage]): Behavior[Submit] =
    Actor.immutable[Submit] { (ctx, submit) =>
      supervisor ! submit
      Actor.same
    }

  // Starts the DummyHcdClient actor in a new ActorSystem and sends it a Submit
  def start(supervisor: ActorRef[SupervisorMessage]): Unit = {
    val root = Actor.deferred[Nothing] { ctx =>
      ctx.spawn(DummyHcdClient.behavior(supervisor), "DummyHcdClient")
      val k1 = KeyType.IntKey.make("encoder")
      val k2 = KeyType.StringKey.make("filter")
      val i1 = k1.set(22, 33, 44)
      val i2 = k2.set("a", "b", "c").withUnits(degree)
      val setup = Setup("Obs001", Prefix("wfos.blue.filter")).add(i1).add(i2)
      supervisor ! Submit(setup, replyTo = ctx.spawnAnonymous(Actor.ignore))

      Actor.empty
    }
    val system = ActorSystem[Nothing](root, "DummyRoot")

  }
}