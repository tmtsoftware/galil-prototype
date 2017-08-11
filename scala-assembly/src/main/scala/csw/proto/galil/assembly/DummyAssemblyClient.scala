package csw.proto.galil.assembly

import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, ActorSystem, Behavior}
import csw.common.framework.models.CommandMsg.Submit
import csw.common.framework.models.SupervisorMsg
import csw.param.commands.Setup
import csw.param.generics.KeyType
import csw.param.models.Prefix
import csw.units.Units.degrees

// Temporary dummy Assembly client to test sending the Assembly a Submit message
object DummyAssemblyClient {

  private def behavior(supervisor: ActorRef[SupervisorMsg]): Behavior[Submit] =
    Actor.immutable[Submit] { (ctx, submit) =>
      supervisor ! submit
      Actor.same
    }

  // Starts the DummyAssemblyClient actor in a new ActorSystem and sends it a Submit
  def start(supervisor: ActorRef[SupervisorMsg]): Unit = {
    val root = Actor.deferred[Nothing] { ctx =>
      ctx.spawn(DummyAssemblyClient.behavior(supervisor), "DummyAssemblyClient")
      val k1 = KeyType.IntKey.make("encoder")
      val k2 = KeyType.StringKey.make("filter")
      val i1 = k1.set(22, 33, 44)
      val i2 = k2.set("a", "b", "c").withUnits(degrees)
      val setup = Setup("Obs001", Prefix("wfos.blue.filter")).add(i1).add(i2)
      supervisor ! Submit(setup, replyTo = ctx.spawnAnonymous(Actor.ignore))

      Actor.empty
    }
    val system = ActorSystem[Nothing](root, "DummyRoot")

  }
}
