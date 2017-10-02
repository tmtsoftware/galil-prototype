package csw.proto.galil.io

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.services.location.scaladsl.ActorSystemFactory
import csw.services.logging.scaladsl.LoggingSystemFactory
import org.scalatest.{BeforeAndAfterAll, FunSuite}

// Note: Before running this test, start the galil "simulator" script
class GalilIoTests extends FunSuite with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystemFactory.remote
  private val localHost = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilIoTests", "0.1", localHost, system)
  val galilIo = GalilIo() // default params: "127.0.0.1", 8888

  override def beforeAll() {
  }

  override def afterAll() {
  }

  test("Test Galil commands") {

    // send two commands separated by ";" (should get two replies)
    val r1 = galilIo.send("TH;TH")
    r1.foreach(r => println(s"Response: ${r.utf8String}"))
    assert(r1.size == 2)

    // Should get empty reply
    val r2 = galilIo.send("noreplycmd")
    r2.foreach(r => println(s"Response: ${r.utf8String}"))
    assert(r2.size == 1)
    assert(r2.head.utf8String == ":")

    // Should get "error" reply
    val r3 = galilIo.send("badcmd")
    r3.foreach(r => println(s"Response: ${r.utf8String}"))
    assert(r3.size == 1)
    assert(r3.head.utf8String == "?")
  }
}
