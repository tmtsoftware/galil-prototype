package csw.proto.galil.hcd

import csw.proto.galil.io.{GalilIo, GalilIoTcp}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.duration._

/**
 * Unit tests for ControllerCommandActor (SDD Section 6.3 - Command Interface)
 * 
 * These tests verify the low-level controller interface implementation:
 * - Command send/receive protocol
 * - Response parsing and error detection
 * - QR polling management
 * - File operation protocol (UL/DL)
 * - Buffer synchronization
 * - Thread coordination
 * 
 * Prerequisites:
 * - Galil controller or simulator must be running
 * - Configure host/port in test or via system properties
 */
class ControllerCommandActorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  // Configure controller connection
  // Try: 1) System property, 2) Environment variable, 3) Default to simulator
  private val galilHost = sys.props.get("galil.host")
    .orElse(sys.env.get("GALIL_HOST"))
    .getOrElse("127.0.0.1")
  
  private val galilPort = sys.props.get("galil.port")
    .orElse(sys.env.get("GALIL_PORT"))
    .getOrElse("8888").toInt
  
  private var galilIo: GalilIo = uninitialized

  override def beforeAll(): Unit = {
    super.beforeAll()
    println(s"ControllerCommandActorTest: Connecting to Galil at $galilHost:$galilPort")
    galilIo = GalilIoTcp(galilHost, galilPort)
  }

  override def afterAll(): Unit = {
    if (galilIo != null) galilIo.close()
    super.afterAll()
  }

  // ========================================
  // Section 6.3.1: Basic Command/Response Protocol
  // ========================================

  test("should send command and receive response") {
    info("Testing basic command/response protocol")
    
    // Send simple query command
    val responses = galilIo.send("MG TIME")
    
    responses should have size 1
    val (cmd, response) = responses.head
    cmd shouldBe "MG TIME"
    response.utf8String should not be empty
    
    info(s"Received: ${response.utf8String.trim}")
  }

  test("should handle multiple semicolon-separated commands") {
    info("Testing compound command handling")
    
    // Send multiple commands in one line.
    val responses = galilIo.send("MG TIME; MG TIME")
    
    responses should have size 2
    responses(0)._1.trim shouldBe "MG TIME"
    responses(1)._1.trim shouldBe "MG TIME"
    
    // Both should have responses
    responses.foreach { case (cmd, resp) =>
      resp.utf8String should not be empty
      info(s"${cmd.trim} → ${resp.utf8String.trim}")
    }
  }

  test("should detect error responses") {
    info("Testing error detection (? response)")
    
    // Send invalid command - Galil returns '?' for unrecognized commands.
    val responses = galilIo.send("INVALID_COMMAND_XYZ")
    
    responses should have size 1
    val (_, response) = responses.head
    
    // Galil responds with '?' for unrecognized commands
    response.utf8String.trim should (equal("?") or startWith("?"))
    
    info("Error correctly detected")
  }

  test("should handle commands with no response data") {
    info("Testing commands that return only colon")
    
    // Commands like HX return only ':'
    val responses = galilIo.send("MG \"test\"")
    
    responses should have size 1
    val (_, response) = responses.head
    
    // Should get ':' or 'test:' depending on command
    response.utf8String should not be empty
    
    info(s"Response: ${response.utf8String.trim}")
  }

  // ========================================
  // Section 6.3.2: Response Format Handling
  // ========================================

  test("should parse numeric responses") {
    info("Testing numeric response parsing")
    
    // Get a numeric value
    val responses = galilIo.send("MG TIME")
    val value = responses.head._2.utf8String.trim.stripSuffix(":")
    
    // Should be parseable as a number
    noException should be thrownBy value.toDouble
    
    info(s"Parsed numeric value: $value")
  }

  test("should parse string responses") {
    info("Testing string response parsing")
    
    // Get controller version (string)
    val responses = galilIo.send("MG version")
    val version = responses.head._2.utf8String.trim.stripSuffix(":")
    
    version should not be empty
    info(s"Controller version: $version")
  }

  test("should handle binary responses (QR)") {
    info("Testing binary response handling")
    
    // QR returns binary data record
    val responses = galilIo.send("QR")
    
    responses should have size 1
    val (_, response) = responses.head
    
    // QR record size = 82 (fixed header + general state) + numAxes * 36 (per-axis block)
    // 4-axis hardware: 226 bytes; 8-axis simulator: 370 bytes
    val size = response.length
    (size - 82) % 36 should be(0)
    size should be >= 118  // at least 1 axis
    
    info(s"Binary QR response correctly received (${response.length} bytes)")
  }

  // ========================================
  // Section 6.3.3: Buffer Management
  // ========================================

  test("should drain buffer between operations") {
    info("Testing buffer draining")
    
    // Send command
    galilIo.send("MG TIME")
    
    // Drain buffer
    val residual = galilIo.drainAndShowBuffer()
    
    // Should be empty or just prompt
    if (residual.nonEmpty) {
      residual should (equal(":") or have length (0))
      info(s"Found expected prompt: '$residual'")
    } else {
      info("Buffer clean")
    }
  }

  test("should handle buffer state after QR") {
    info("Testing buffer state after QR command")
    
    // Send QR
    galilIo.send("QR")
    
    // Check buffer
    val residual = galilIo.drainAndShowBuffer()
    
    // Should be clean after QR
    residual should have length 0
    
    info("Buffer clean after QR")
  }

  // ========================================
  // Section 6.3.5: Thread Coordination
  // ========================================

  test("should handle synchronized command access") {
    info("Testing thread-safe command execution")
    
    // Send commands from "concurrent" operations
    // (In single-threaded test, we're verifying the sync mechanism exists)
    
    val result1 = galilIo.synchronized {
      galilIo.send("MG TIME")
    }
    
    val result2 = galilIo.synchronized {
      galilIo.send("MG version")
    }
    
    result1 should have size 1
    result2 should have size 1
    
    info("Synchronized access working")
  }

  // ========================================
  // Section 6.3.6: QR Polling Management
  // ========================================

  test("should handle QR polling cycle") {
    info("Testing QR polling cycle")
    
    // Execute QR
    val responses = galilIo.send("QR")
    
    responses should have size 1
    val (_, dataRecord) = responses.head
    
    // QR record size = 82 (fixed) + numAxes * 36; 4-axis: 226, 8-axis: 370
    val size = dataRecord.length
    (size - 82) % 36 should be(0)
    size should be >= 118
    
    // First byte is header
    val header = dataRecord(0)
    info(f"QR header byte: 0x${header & 0xFF}%02X")
    
    // Parse sample number (bytes 1-2, little endian)
    val sampleLow = dataRecord(1) & 0xFF
    val sampleHigh = dataRecord(2) & 0xFF
    val sampleNumber = sampleLow | (sampleHigh << 8)
    
    info(s"Sample number: $sampleNumber")
    info(s"DataRecord size: ${dataRecord.length} bytes")
  }

  test("should distinguish QR from text commands") {
    info("Testing QR vs text command differentiation")
    
    // Text command
    val textResp = galilIo.send("MG TIME")
    val textData = textResp.head._2
    
    // Binary command
    val binaryResp = galilIo.send("QR")
    val binaryData = binaryResp.head._2
    
    // QR record size = 82 (fixed) + numAxes * 36; 4-axis: 226, 8-axis: 370
    val binarySize = binaryData.length
    (binarySize - 82) % 36 should be(0)
    binarySize should be >= 118
    
    // Text should be variable length and ASCII-ish
    textData.length should be < 100
    
    info(s"Text: ${textData.length} bytes, Binary: ${binaryData.length} bytes")
    info("Text vs binary correctly distinguished")
  }

  // ========================================
  // Section 6.3.7: Error Handling
  // ========================================

  test("should handle timeout gracefully") {
    info("Testing timeout handling")
    
    // Send valid command - should complete quickly
    val start = System.currentTimeMillis()
    val responses = galilIo.send("MG TIME")
    val duration = System.currentTimeMillis() - start
    
    responses should have size 1
    duration should be < 1000L  // Should respond in < 1 second
    
    info(s"Command completed in ${duration}ms")
  }

  test("should recover from error state") {
    info("Testing error recovery")
    
    // Send bad command
    galilIo.send("INVALID_CMD")
    
    // Should still be able to send good command
    val responses = galilIo.send("MG TIME")
    
    responses should have size 1
    responses.head._2.utf8String should not be empty
    
    info("Controller recovered from error")
  }

  // ========================================
  // Section 6.3.8: Performance Characteristics
  // ========================================

  test("should meet command latency requirements") {
    info("Testing command latency")
    
    val iterations = 10
    val latencies = (1 to iterations).map { _ =>
      val start = System.nanoTime()
      galilIo.send("MG TIME")
      val end = System.nanoTime()
      (end - start) / 1000000.0  // Convert to ms
    }
    
    val avgLatency = latencies.sum / iterations
    val maxLatency = latencies.max
    
    info(f"Average latency: $avgLatency%.2f ms")
    info(f"Max latency: $maxLatency%.2f ms")
    
    // Reasonable expectations for Ethernet connection
    avgLatency should be < 100.0  // < 100ms average
  }

  test("should handle burst commands") {
    info("Testing burst command handling")
    
    val start = System.currentTimeMillis()
    
    // Send 10 commands rapidly
    (1 to 10).foreach { i =>
      val responses = galilIo.send(s"MG TIME")
      responses should have size 1
    }
    
    val duration = System.currentTimeMillis() - start
    info(s"10 commands completed in ${duration}ms")
    
    // Should complete reasonably fast
    duration should be < 2000L  // < 2 seconds for 10 commands
  }

  // ========================================
  // Thread-selection policy (S85): 1-7 preferred, thread 0 as last resort
  //
  // Pure-function tests of ControllerCommandActor.selectThread — the policy
  // behind allocateThread. No controller required. The controller has 8
  // threads for up to 8 motors; reserving thread 0 outright would cap
  // simultaneous motion at 7 axes.
  // ========================================

  test("selectThread prefers threads 1-7 in ascending order") {
    ControllerCommandActor.selectThread(0x00, Set.empty) shouldBe Some(1)
    // threads 1,2 hardware-busy -> next preferred is 3, NOT 0
    ControllerCommandActor.selectThread(0x06, Set.empty) shouldBe Some(3)
    // thread 0 hardware-busy (#Init/#Setup running) is irrelevant while 1-7 have room
    ControllerCommandActor.selectThread(0x01, Set.empty) shouldBe Some(1)
  }

  test("selectThread lends thread 0 only when threads 1-7 are exhausted") {
    // 1-7 hardware-busy, 0 free -> last resort fires (the 8-motor case)
    ControllerCommandActor.selectThread(0xFE, Set.empty) shouldBe Some(0)
    // mixed exhaustion: 1-4 hardware-busy, 5-7 reserved pending observation -> 0
    ControllerCommandActor.selectThread(0x1E, Set(5, 6, 7)) shouldBe Some(0)
    // all 8 hardware-busy -> genuine exhaustion
    ControllerCommandActor.selectThread(0xFF, Set.empty) shouldBe None
  }

  test("selectThread applies the observation gate to thread 0 like any other thread") {
    // 1-7 hardware-busy; 0 hardware-free but awaiting completion attribution -> None
    ControllerCommandActor.selectThread(0xFE, Set(0)) shouldBe None
    // everything hardware-free but all reserved -> None (transient back-pressure)
    ControllerCommandActor.selectThread(0x00, (0 to 7).toSet) shouldBe None
    // 1-7 reserved, 0 free and observed -> 0
    ControllerCommandActor.selectThread(0x00, (1 to 7).toSet) shouldBe Some(0)
  }
}
