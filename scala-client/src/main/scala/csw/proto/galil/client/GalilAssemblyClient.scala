package csw.proto.galil.client

import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, ActorSystem, Behavior}
import csw.common.framework.models.CommandMessage.Submit
import csw.common.framework.models.SupervisorMessage
import csw.param.commands.Setup
import csw.param.generics.KeyType
import csw.param.models.Prefix
import csw.units.Units.degree

// A client to test locating and communicating with the Galil assembly
object GalilAssemblyClient {

  private def behavior(supervisor: ActorRef[SupervisorMessage]): Behavior[Submit] =
    Actor.immutable[Submit] { (ctx, submit) =>
      supervisor ! submit
      Actor.same
    }

  // Starts the DummyAssemblyClient actor in a new ActorSystem and sends it a Submit
  def start(supervisor: ActorRef[SupervisorMessage]): Unit = {
    val root = Actor.deferred[Nothing] { ctx =>
      ctx.spawn(GalilAssemblyClient.behavior(supervisor), "DummyAssemblyClient")
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
