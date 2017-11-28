package csw.proto.galil.io

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.util.ByteString
import csw.services.location.commons.ActorSystemFactory
import csw.services.logging.scaladsl.LoggingSystemFactory
import org.scalatest.{BeforeAndAfterAll, FunSuite}

// Note: Before running this test, start the galil "simulator" script
class GalilIoTests extends FunSuite with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystemFactory.remote
  private val localHost = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilIoTests", "0.1", localHost, system)
//  val galilIo = GalilIoTcp() // default params: "127.0.0.1", 8888
  val galilIo = GalilIoTcp("192.168.2.2", 23) // temp: galil device

  override def beforeAll() {
  }

  override def afterAll() {
  }

  test("Test Galil commands") {

    // send two commands separated by ";" (should get two replies)
    val r0 = galilIo.send("TH")
    r0.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r0.head._1 == "TH")
    assert(r0.size == 1)

    val r1 = galilIo.send("TH;TH")
    r1.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r1.head._1 == "TH")
    assert(r1.tail.head._1 == "TH")
    assert(r1.size == 2)

    // Should get empty reply
    val r2 = galilIo.send("NO")
    r2.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r2.size == 1)
    assert(r2.head._2.utf8String == "")

    // Check error (should be none)
    val r3 = galilIo.send("TC0")
    r3.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r3.size == 1)
    assert(r3.head._1 == "TC0")
    assert(r3.head._2.utf8String == "0")

    // Should get "error" reply
    val r4 = galilIo.send("XX")
    r4.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r4.size == 1)
    assert(r4.head._2.utf8String == "?")

    // Check error (should be unknown command)
    val r5 = galilIo.send("TC1")
    r5.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r5.size == 1)
    assert(r5.head._1 == "TC1")
    assert(r5.head._2.utf8String == "1 Unrecognized command")

    // Check error (should be 0)
    val r6 = galilIo.send("TC1")
    r6.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r6.size == 1)
    assert(r6.head._1 == "TC1")
    assert(r6.head._2.utf8String == "0")
  }

  test("Test DataRecord generation and parsing") {
    val r = galilIo.send("QR")
    val dr = DataRecord(r.head._2)
    println(s"Data Record: $dr")
    val bs = ByteString(DataRecord.generateByteBuffer(dr))
    val dr2 = DataRecord(bs)
    println(s"Generated Data Record: $dr2")
  }
}
