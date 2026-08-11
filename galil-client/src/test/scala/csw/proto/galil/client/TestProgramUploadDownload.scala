package csw.proto.galil.client

import java.net.InetAddress
import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import org.apache.pekko.util.Timeout
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.prefix.models.Prefix

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
 * Test client for upload/download program functionality
 * 
 * Prerequisites:
 * 1. CSW services running (csw-services start)
 * 2. Galil HCD running with RegisterOnly
 * 3. sampleHCD_2steppers.dmc file in galil-hcd/src/main/resources/programs/
 */
object TestProgramUploadDownload {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "TestProgramUploadDownload")
  implicit lazy val ec: ExecutionContextExecutor               = typedSystem.executionContext

  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val galilHcdClient  = GalilHcdClient(Prefix("CSW.galil.client"), locationService)
  private val maybeObsId      = None
  private val host            = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("TestProgramUploadDownload", "0.1", host, typedSystem)
  implicit val timeout: Timeout = Timeout(60.seconds)  // Upload/download may take longer
  private val log               = GenericLoggerFactory.getLogger

  def main(args: Array[String]): Unit = {

    log.info("Starting Program Upload/Download Test")
    println("\n=== Testing Galil Program Upload/Download ===\n")

    try {
      // Test 1: Download current programs from controller
      println("--- Test 1: Download Programs ---")
      val downloadFilename = "downloaded_current.dmc"
      println(s"Downloading programs from controller to: $downloadFilename")
      
      val resp1 = Await.result(galilHcdClient.downloadProgram(downloadFilename, maybeObsId), 60.seconds)
      println(s"1. downloadProgram: $resp1")
      resp1 match {
        case csw.params.commands.CommandResponse.Completed(_, result) =>
          result.get(csw.params.core.generics.KeyType.StringKey.make("filename")) match {
            case Some(param) =>
              println(s"   Programs saved to: ${param.head}")
            case None =>
              println(s"   Programs downloaded (no filepath returned)")
          }
        case error =>
          println(s"   ERROR: $error")
      }
      
      // Give controller time to complete and flush buffers
      println("\n   Waiting 3 seconds for controller to settle...")
      Thread.sleep(3000)
      
      // Test 2: Upload a known-good program
      println("\n--- Test 2: Upload Program ---")
      val uploadFilename = "protoHCD_lab.dmc"
      println(s"Uploading program from: $uploadFilename")
      
      val resp2 = Await.result(galilHcdClient.uploadProgram(uploadFilename, maybeObsId), 60.seconds)
      println(s"2. uploadProgram: $resp2")
      resp2 match {
        case csw.params.commands.CommandResponse.Completed(_, _) =>
          println(s"   Program uploaded successfully!")
        case error =>
          println(s"   ERROR: $error")
      }
      
      // Test 3: Verify upload by downloading again
      println("\n--- Test 3: Verify Upload ---")
      val verifyFilename = "downloaded_after_upload.dmc"
      println(s"Downloading programs again to verify: $verifyFilename")
      
      // Give controller extra time to settle after upload
      println("   Waiting 5 seconds for controller to complete upload...")
      Thread.sleep(5000)
      
      val resp3 = Await.result(galilHcdClient.downloadProgram(verifyFilename, maybeObsId), 60.seconds)
      println(s"3. downloadProgram (verify): $resp3")
      resp3 match {
        case csw.params.commands.CommandResponse.Completed(_, result) =>
          result.get(csw.params.core.generics.KeyType.StringKey.make("filename")) match {
            case Some(param) =>
              println(s"   Programs saved to: ${param.head}")
              println(s"\n   You can now compare files:")
              println(s"   - $downloadFilename (before upload)")
              println(s"   - $verifyFilename (after upload)")
            case None =>
              println(s"   Programs downloaded")
          }
        case error =>
          println(s"   ERROR: $error")
      }
      
      // Test 4: Check version variable
      println("\n--- Test 4: Check Version ---")
      val resp4 = Await.result(galilHcdClient.getVariable("version", maybeObsId), 10.seconds)
      println(s"4. getVariable('version'): $resp4")
      resp4 match {
        case csw.params.commands.CommandResponse.Completed(_, result) =>
          result.get(csw.params.core.generics.KeyType.StringKey.make("response")) match {
            case Some(param) =>
              println(s"   Version: ${param.head}")
            case None =>
              println(s"   Version command completed but no response returned")
          }
        case error =>
          println(s"   ERROR: $error")
      }
      
      println("\n=== All Tests Complete ===\n")
      
    } catch {
      case ex: Exception =>
        println(s"\n=== TEST FAILED ===")
        println(s"Error: ${ex.getMessage}")
        ex.printStackTrace()
    } finally {
      typedSystem.terminate()
      typedSystem.whenTerminated.onComplete(_ => System.exit(0))
    }
  }
}