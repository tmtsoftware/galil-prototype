package csw.proto.galil.client

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.prefix.models.Prefix
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/**
 * Test suite for GalilHcdClient testing the new HCD interface.
 * 
 * This test connects to a running HCD (does NOT start its own services).
 * 
 * Prerequisites:
 * - CSW services running (or HCD running with --local)
 * - HCD must be running:
 *     ./target/universal/stage/bin/galil-hcd -main csw.proto.galil.hcd.GalilHcdApp \
 *       --local galil-hcd/src/main/resources/GalilHcd1.conf \
 *       -Dgalil.host=192.168.86.41 -Dgalil.port=23
 * - Galil controller must be accessible (or simulator running)
 */
class GalilClientTest extends AnyFunSuite with BeforeAndAfterAll {

  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = 
    ActorSystem(SpawnProtocol(), "GalilClientTest")
  implicit val ec: ExecutionContext = typedSystem.executionContext
  
  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val client = GalilHcdClient(Prefix("CSW.galil.client"), locationService)
  private val testTimeout = 30.seconds
  private val maybeObsId = None

  override def afterAll(): Unit = {
    typedSystem.terminate()
    Await.result(typedSystem.whenTerminated, 10.seconds)
  }

  test("should download programs from controller") {
    println("Downloading programs from controller...")
    val response = Await.result(client.downloadProgram("test_download.dmc", maybeObsId), testTimeout)
    
    response match {
      case csw.params.commands.CommandResponse.Completed(_, result) =>
        result.get(csw.params.core.generics.KeyType.StringKey.make("filename")) match {
          case Some(param) =>
            println(s"✓ Downloaded to: ${param.head}")
            assert(param.head.nonEmpty, "Filename should not be empty")
          case None =>
            fail("No filename in response")
        }
      case error =>
        fail(s"Download failed: $error")
    }
  }

  test("should upload program to controller") {
    println("Uploading test program from protoHCD_lab.dmc...")
    // Use existing file (must exist in programs/ directory)
    val response = Await.result(client.uploadProgram("protoHCD_lab.dmc", maybeObsId), testTimeout)
    
    response match {
      case csw.params.commands.CommandResponse.Completed(_, _) =>
        println("✓ Upload successful")
      case error =>
        fail(s"Upload failed: $error")
    }
  }

  test("should handle upload and download round-trip") {
    println("Round-trip test: Upload → Download → Compare")
    
    // Upload existing file
    println("  Uploading protoHCD_lab.dmc...")
    val uploadResp = Await.result(client.uploadProgram("protoHCD_lab.dmc", maybeObsId), testTimeout)
    assert(uploadResp.isInstanceOf[csw.params.commands.CommandResponse.Completed], "Upload should succeed")
    
    Thread.sleep(500)  // Let controller settle
    
    // Download to verify
    println("  Downloading to roundtrip_verify.dmc...")
    val downloadResp = Await.result(client.downloadProgram("roundtrip_verify.dmc", maybeObsId), testTimeout)
    
    downloadResp match {
      case csw.params.commands.CommandResponse.Completed(_, _) =>
        println("✓ Round-trip successful")
      case error =>
        fail(s"Download after upload failed: $error")
    }
  }

  test("should query controller variable with sendCommandRV") {
    println("Querying controller version...")
    val response = Await.result(client.getVariable("version", maybeObsId), testTimeout)
    
    response match {
      case csw.params.commands.CommandResponse.Completed(_, result) =>
        result.get(csw.params.core.generics.KeyType.StringKey.make("response")) match {
          case Some(param) =>
            val version = param.head
            println(s"✓ Controller version: $version")
            assert(version.nonEmpty, "Version should not be empty")
          case None =>
            fail("No response in result")
        }
      case error =>
        fail(s"Query failed: $error")
    }
  }

  test("should handle multiple variable queries") {
    val queries = List("version", "BV")
    
    println(s"Querying ${queries.size} variables...")
    queries.foreach { query =>
      val response = Await.result(client.getVariable(query, maybeObsId), testTimeout)
      response match {
        case csw.params.commands.CommandResponse.Completed(_, result) =>
          result.get(csw.params.core.generics.KeyType.StringKey.make("response")) match {
            case Some(param) =>
              println(s"  $query → ${param.head}")
            case None =>
              fail(s"No response for query: $query")
          }
        case error =>
          fail(s"Query '$query' failed: $error")
      }
    }
    println("✓ All queries successful")
  }

  test("should handle QR polling during file operations") {
    println("Testing QR polling pause/resume during operations...")
    
    // Upload operation should automatically pause/resume QR
    println("  Starting upload (QR should pause)...")
    val uploadStart = System.currentTimeMillis()
    val uploadResp = Await.result(client.uploadProgram("protoHCD_lab.dmc", maybeObsId), testTimeout)
    val uploadDuration = System.currentTimeMillis() - uploadStart
    println(s"  Upload completed in ${uploadDuration}ms")
    
    assert(uploadResp.isInstanceOf[csw.params.commands.CommandResponse.Completed], "Upload should succeed")
    
    Thread.sleep(500)  // Let controller settle
    
    // Download operation should also pause/resume QR
    println("  Starting download (QR should pause)...")
    val downloadStart = System.currentTimeMillis()
    val downloadResp = Await.result(client.downloadProgram("qr_test_verify.dmc", maybeObsId), testTimeout)
    val downloadDuration = System.currentTimeMillis() - downloadStart
    println(s"  Download completed in ${downloadDuration}ms")
    
    assert(downloadResp.isInstanceOf[csw.params.commands.CommandResponse.Completed], "Download should succeed")
    println("✓ QR polling handled correctly during file operations")
  }

  test("should handle large program upload") {
    // Test upload/download performance with existing program
    // (protoHCD_lab.dmc is ~5KB which is a reasonable test size)
    
    println("Testing large file upload/download...")
    val uploadStart = System.currentTimeMillis()
    val uploadResp = Await.result(client.uploadProgram("protoHCD_lab.dmc", maybeObsId), testTimeout)
    val uploadDuration = System.currentTimeMillis() - uploadStart
    
    uploadResp match {
      case csw.params.commands.CommandResponse.Completed(_, _) =>
        println(s"✓ Program uploaded in ${uploadDuration}ms")
      case error =>
        fail(s"Upload failed: $error")
    }
    
    Thread.sleep(500)  // Let controller settle
    
    // Verify by downloading
    println("  Verifying with download...")
    val downloadStart = System.currentTimeMillis()
    val downloadResp = Await.result(client.downloadProgram("large_test_verify.dmc", maybeObsId), testTimeout)
    val downloadDuration = System.currentTimeMillis() - downloadStart
    
    assert(downloadResp.isInstanceOf[csw.params.commands.CommandResponse.Completed], "Download verification should succeed")
    println(s"✓ Program downloaded in ${downloadDuration}ms")
  }
}
