package csw.proto.galil.client

import csw.messages.ccs.commands.CommandName
import csw.messages.params.models.Prefix

import scala.concurrent.Await
import scala.concurrent.duration._

// A client to test locating and communicating with the Galil HCD
object GalilHcdClientApp extends App {

  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"), CommandName("filter"))
  private val maybeObsId = None
  galilHcdClient.init()

  val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
  println(s"setRelTarget: $resp1")
  val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
  println(s"getRelTarget: $resp2")
}

