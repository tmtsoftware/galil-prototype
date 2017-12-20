package csw.proto.galil.client

import csw.messages.ccs.commands.CommandName
import csw.messages.ccs.commands.CommandResponse.{Completed, CompletedWithResult}
import csw.messages.params.models.Prefix
import csw.proto.galil.client.GalilHcdClientApp.{galilHcdClient, maybeObsId}
import org.scalatest.{FunSuite, Ignore}

import scala.concurrent.Await
import scala.concurrent.duration._

// Note: Test assumes that location service (csw-cluster-seed), galil-hcd and galil-simulator are running
@Ignore
class GalilClientTest extends FunSuite {
  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"), CommandName("filter"))
  private val maybeObsId = None

  test("Test ") {
    galilHcdClient.init()

    val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
    println(s"setRelTarget: $resp1")
    assert(resp1.isInstanceOf[Completed])

    val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
    println(s"getRelTarget: $resp2")
    assert(resp2.isInstanceOf[CompletedWithResult])
  }
}
