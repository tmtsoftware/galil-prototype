package csw.proto.galil.io

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.util.ByteString
import csw.location.client.ActorSystemFactory
import csw.logging.scaladsl.LoggingSystemFactory
import csw.params.commands.Result
import csw.params.core.models.Prefix
import org.scalatest.{BeforeAndAfterAll, FunSuite}

//noinspection ComparingLength
// Note: Before running this test, start the galil "simulator" script
class GalilIoTests extends FunSuite with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystemFactory.remote
  private val localHost = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilIoTests", "0.1", localHost, system)
  val galilIo = GalilIoTcp() // default params: "127.0.0.1", 8888, can also use ssh tunnel to test on device

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
    assert(r3.head._2.utf8String.trim == "0")

    // XXX TODO: FIXME
//    // Should get "error" reply
//    val r4 = galilIo.send("XX")
//    r4.foreach(r => println(s"Response: ${r._2.utf8String}"))
//    assert(r4.size == 1)
//    assert(r4.head._2.utf8String == "?")
//
//    // Check error (should be unknown command)
//    val r5 = galilIo.send("TC1")
//    r5.foreach(r => println(s"Response: ${r._2.utf8String}"))
//    assert(r5.size == 1)
//    assert(r5.head._1 == "TC1")
//    assert(r5.head._2.utf8String == "1 Unrecognized command")

    // Check error (should be 0)
    val r6 = galilIo.send("TC1")
    r6.foreach(r => println(s"Response: ${r._2.utf8String}"))
    assert(r6.size == 1)
    assert(r6.head._1 == "TC1")
    assert(r6.head._2.utf8String.trim == "0")
  }

  test("Test DataRecord generation and parsing") {
    val r = galilIo.send("QR")
    val bs1 = r.head._2

    // Test creating a DataRecord from the bytes returned from the Galil device
    val dr1 = DataRecord(bs1)
    println(s"\nData Record (size: ${bs1.size}): $dr1")
    val bs2 = ByteString(dr1.toByteBuffer)
    println(s"\nGenerated Data Record Size: ${bs2.size}")
    val dr2 = DataRecord(bs2)
    println(s"\nGenerated Data Record: $dr2")
    assert(dr1.toString == dr2.toString)

    // Test creating a DataRecord from a paramset (wrapped in a Result object, which contains a prefix and a param set)
    val paramSet = dr1.toParamSet
    println(s"DataRecord ParamSet: $paramSet\n")
    val result = Result(Prefix("test.one"), paramSet)
    val dr3 = DataRecord(result)
    assert(dr1.toString == dr3.toString)
  }
}
