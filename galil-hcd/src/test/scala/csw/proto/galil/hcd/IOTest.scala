package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import csw.command.client.CommandResponseManager
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.{CommandName, Setup}
import csw.params.core.models.Id
import csw.prefix.models.Prefix
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState, Header}

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tests for I/O functionality:
 *
 * Section 1: Digital I/O extraction from QR DataRecord
 *   - StatusMonitor correctly unpacks generalState.inputs/outputs bytes
 *     into 16-element Boolean arrays in HcdState.
 *   - Verifies bit ordering, multi-byte layout, and isolation between DI and DO.
 *
 * Section 2: setBit command dispatch
 *   - CommandHandlerActor sends "SB n" for value=1 and "CB n" for value=0.
 *   - Verifies correct Galil command string for several addresses.
 *
 * Section 3: Analog input polling
 *   - StatusMonitor.PollAnalogInputs sends MG @AN[1..8] to the controller.
 *   - Controller responses are parsed as volts and stored in HcdState.analogInputs.
 *   - Partial failures (bad parse, error response) leave the slot at 0 without
 *     crashing the actor.
 */
class IOTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit      = ActorTestKit()
  private val hcdPrefix    = Prefix("APS.ICS.HCD.GalilMotion")
  private val loggerFactory = new LoggerFactory(hcdPrefix)

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** Build a DataRecord with controlled inputs/outputs bytes. */
  private def makeDataRecord(
    inputBytes:  Array[Byte] = Array.fill(10)(0.toByte),
    outputBytes: Array[Byte] = Array.fill(10)(0.toByte),
    threadStatus: Byte = 0
  ): DataRecord =
    val header = Header(List("A", "B"))
    val gs = GeneralState(
      sampleNumber = 1,
      inputs = inputBytes, outputs = outputBytes,
      ethernetHandleStatus = Array.fill(8)(0.toByte),
      errorCode = 0, threadStatus = threadStatus, amplifierStatus = 0,
      contourModeSegmentCount = 0, contourModeBufferSpaceRemaining = 0,
      sPlaneSegmentCount = 0, sPlaneMoveStatus = 0, sPlaneDistanceTraveled = 0,
      sPlaneBufferSpaceRemaining = 0, tPlaneSegmentCount = 0, tPlaneMoveStatus = 0,
      tPlaneDistanceTraveled = 0, tPlaneBufferSpaceRemaining = 0
    )
    DataRecord(header, gs, Array(GalilAxisStatus(), GalilAxisStatus()))

  /** Spawn IS + StatusMonitor with a controllable mock CI actor. */
  private def createMonitorWithMock(
    mockBehavior: Behavior[GalilCommandMessage]
  ): (ActorRef[StatusMonitor.Command], ActorRef[InternalStateActor.Command]) =
    val is = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val ci = testKit.spawn(mockBehavior)
    val sm = testKit.spawn(
      StatusMonitor(ci, is, loggerFactory, standbyPollingRateHz = 1.0, actionPollingRateHz = 1.0)
    )
    (sm, is)

  /** Read HcdState back from IS synchronously. */
  private def getHcdState(is: ActorRef[InternalStateActor.Command]): HcdState =
    val probe = testKit.createTestProbe[HcdState]()
    is ! InternalStateActor.GetHcdState(probe.ref)
    probe.receiveMessage()

  // ── Section 1: DIO extraction from QR ────────────────────────────────────

  test("DIO: all inputs zero → digitalInputs all false") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    sm ! StatusMonitor.QRResponse(makeDataRecord())
    Thread.sleep(100)
    val state = getHcdState(is)
    state.digitalInputs  should have length 16
    state.digitalInputs  should contain only false
    state.digitalOutputs should have length 16
    state.digitalOutputs should contain only false
  }

  test("DIO: byte 0 bit 0 → digitalInputs(0) true, rest false") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    val inputs = Array.fill(10)(0.toByte)
    inputs(0) = 0x01.toByte  // bit 0 only
    sm ! StatusMonitor.QRResponse(makeDataRecord(inputBytes = inputs))
    Thread.sleep(100)
    val state = getHcdState(is)
    state.digitalInputs(0) shouldBe true
    state.digitalInputs.drop(1) should contain only false
  }

  test("DIO: byte 0 bit 7 → digitalInputs(7) true") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    val inputs = Array.fill(10)(0.toByte)
    inputs(0) = 0x80.toByte  // bit 7 only
    sm ! StatusMonitor.QRResponse(makeDataRecord(inputBytes = inputs))
    Thread.sleep(100)
    val state = getHcdState(is)
    state.digitalInputs(7) shouldBe true
    state.digitalInputs.patch(7, Seq(), 1) should contain only false
  }

  test("DIO: byte 1 bit 0 → digitalInputs(8) true (second byte = bits 8-15)") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    val inputs = Array.fill(10)(0.toByte)
    inputs(1) = 0x01.toByte  // bit 8
    sm ! StatusMonitor.QRResponse(makeDataRecord(inputBytes = inputs))
    Thread.sleep(100)
    val state = getHcdState(is)
    state.digitalInputs(8) shouldBe true
    state.digitalInputs.patch(8, Seq(), 1) should contain only false
  }

  test("DIO: byte 0 all bits set → digitalInputs(0..7) all true, (8..15) false") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    val inputs = Array.fill(10)(0.toByte)
    inputs(0) = 0xFF.toByte
    sm ! StatusMonitor.QRResponse(makeDataRecord(inputBytes = inputs))
    Thread.sleep(100)
    val state = getHcdState(is)
    state.digitalInputs.take(8) should contain only true
    state.digitalInputs.drop(8) should contain only false
  }

  test("DIO: both bytes set → digitalInputs(0..15) all true") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    val inputs = Array.fill(10)(0.toByte)
    inputs(0) = 0xFF.toByte
    inputs(1) = 0xFF.toByte
    sm ! StatusMonitor.QRResponse(makeDataRecord(inputBytes = inputs))
    Thread.sleep(100)
    val state = getHcdState(is)
    state.digitalInputs should contain only true
  }

  test("DIO: outputs are independent of inputs") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    val outputs = Array.fill(10)(0.toByte)
    outputs(0) = 0x05.toByte  // bits 0 and 2
    sm ! StatusMonitor.QRResponse(makeDataRecord(outputBytes = outputs))
    Thread.sleep(100)
    val state = getHcdState(is)
    state.digitalInputs  should contain only false
    state.digitalOutputs(0) shouldBe true
    state.digitalOutputs(1) shouldBe false
    state.digitalOutputs(2) shouldBe true
    state.digitalOutputs.drop(3) should contain only false
  }

  test("DIO: alternating bit pattern preserved correctly") {
    val (sm, is) = createMonitorWithMock(Behaviors.receiveMessage(_ => Behaviors.same))
    val inputs = Array.fill(10)(0.toByte)
    inputs(0) = 0xAA.toByte  // bits 1,3,5,7
    sm ! StatusMonitor.QRResponse(makeDataRecord(inputBytes = inputs))
    Thread.sleep(100)
    val state = getHcdState(is)
    // 0xAA = 10101010 → bits 1,3,5,7 true; 0,2,4,6 false
    state.digitalInputs(0) shouldBe false
    state.digitalInputs(1) shouldBe true
    state.digitalInputs(2) shouldBe false
    state.digitalInputs(3) shouldBe true
    state.digitalInputs(4) shouldBe false
    state.digitalInputs(5) shouldBe true
    state.digitalInputs(6) shouldBe false
    state.digitalInputs(7) shouldBe true
    state.digitalInputs.drop(8) should contain only false
  }

  // ── Section 2: setBit command dispatch ───────────────────────────────────

  // Reuse MockCIActor pattern from LongRunningCommandTest
  private object MockCIForIO:
    val commandLog = new ConcurrentLinkedQueue[String]()

    def behavior(): Behavior[GalilCommandMessage] =
      Behaviors.receiveMessage {
        case GalilCommandMessage.SendCommand(cmdString, replyTo) =>
          commandLog.add(cmdString)
          replyTo ! GalilCommandMessage.SendCommandResult(":")
          Behaviors.same
        case _ =>
          Behaviors.same
      }

    def clear(): Unit = commandLog.clear()
    def commands: List[String] =
      import scala.jdk.CollectionConverters._
      commandLog.asScala.toList

  private def sendSetBit(
    handler: ActorRef[CommandHandlerActor.Command],
    address: Int,
    value: Int
  ): Unit =
    var setup = Setup(hcdPrefix, CommandName("setBit"), None)
    setup = setup.add(SetBitCommand.addressKey.set(address))
    setup = setup.add(SetBitCommand.valueKey.set(value))
    handler ! CommandHandlerActor.HandleCommand(setup, Id(), None)
    Thread.sleep(100)

  private def createHandlerWithMockIO(): ActorRef[CommandHandlerActor.Command] =
    MockCIForIO.clear()
    val is = testKit.spawn(InternalStateActor(HcdState()))
    val ci = testKit.spawn(MockCIForIO.behavior())
    val sm = testKit.spawn(Behaviors.receiveMessage[StatusMonitor.Command](_ => Behaviors.same))
    testKit.spawn(CommandHandlerActor.behavior(ci, is, null, loggerFactory, sm))

  test("setBit: value=1 sends SB command with correct address") {
    val handler = createHandlerWithMockIO()
    sendSetBit(handler, 3, 1)
    MockCIForIO.commands should contain("SB 3")
  }

  test("setBit: value=0 sends CB command with correct address") {
    val handler = createHandlerWithMockIO()
    sendSetBit(handler, 5, 0)
    MockCIForIO.commands should contain("CB 5")
  }

  test("setBit: non-zero value (not 1) also sends SB") {
    val handler = createHandlerWithMockIO()
    sendSetBit(handler, 2, 255)
    MockCIForIO.commands should contain("SB 2")
  }

  test("setBit: address 1 → SB 1") {
    val handler = createHandlerWithMockIO()
    sendSetBit(handler, 1, 1)
    MockCIForIO.commands should contain("SB 1")
  }

  test("setBit: address 8 → CB 8") {
    val handler = createHandlerWithMockIO()
    sendSetBit(handler, 8, 0)
    MockCIForIO.commands should contain("CB 8")
  }

  // ── Section 3: Analog input polling ──────────────────────────────────────

  /** Build a mock CI that responds to MG @AN[n] with given voltages (1-indexed). */
  private def aiMockBehavior(voltages: Map[Int, String]): Behavior[GalilCommandMessage] =
    Behaviors.receiveMessage {
      case GalilCommandMessage.SendCommand(cmd, replyTo) =>
        // Handles compound "MG @AN[1],@AN[2],...,@AN[8]"
        // Returns space-separated values on one line, matching hardware format
        val response = cmd.trim match
          case s if s.startsWith("MG ") && s.contains("@AN[") =>
            val tokens = s.stripPrefix("MG ").split(',').map(_.trim)
            tokens.map { token =>
              val ch = token.stripPrefix("@AN[").stripSuffix("]").toIntOption.getOrElse(-1)
              voltages.getOrElse(ch, "0.0000")
            }.mkString(" ")
          case _ => ":"
        replyTo ! GalilCommandMessage.SendCommandResult(response)
        Behaviors.same
      case GalilCommandMessage.GetQR(replyTo) =>
        Behaviors.same  // suppress QR noise in these tests
      case _ =>
        Behaviors.same
    }

  test("AI poll: MG @AN[1..8] responses populate analogInputs correctly") {
    val voltages = Map(1->"-1.2345", 2->"2.5839", 3->"0.0000", 4->"5.0000",
                       5->"-5.0000", 6->"1.1111", 7->"3.3333", 8->"-0.0001")
    val (sm, is) = createMonitorWithMock(aiMockBehavior(voltages))
    sm ! StatusMonitor.PollAnalogInputs
    Thread.sleep(200)
    val state = getHcdState(is)
    state.analogInputs should have length 8
    state.analogInputs(0) shouldBe (-1.2345f +- 0.001f)
    state.analogInputs(1) shouldBe (2.5839f  +- 0.001f)
    state.analogInputs(2) shouldBe (0.0f     +- 0.001f)
    state.analogInputs(3) shouldBe (5.0f     +- 0.001f)
    state.analogInputs(4) shouldBe (-5.0f    +- 0.001f)
    state.analogInputs(5) shouldBe (1.1111f  +- 0.001f)
    state.analogInputs(6) shouldBe (3.3333f  +- 0.001f)
    state.analogInputs(7) shouldBe (-0.0001f +- 0.001f)
  }

  test("AI poll: sends a single compound MG @AN[1..8] command") {
    val cmdLog = new ConcurrentLinkedQueue[String]()
    val loggingMock: Behavior[GalilCommandMessage] = Behaviors.receiveMessage {
      case GalilCommandMessage.SendCommand(cmd, replyTo) =>
        cmdLog.add(cmd)
        replyTo ! GalilCommandMessage.SendCommandResult(
          (1 to 8).map(_ => "2.5000").mkString(" "))
        Behaviors.same
      case _ => Behaviors.same
    }
    val (sm, _) = createMonitorWithMock(loggingMock)
    sm ! StatusMonitor.PollAnalogInputs
    Thread.sleep(200)
    import scala.jdk.CollectionConverters._
    val cmds = cmdLog.asScala.toList.filter(_.startsWith("MG "))
    // Expect exactly one compound command covering all 8 channels
    cmds should have length 1
    cmds.head shouldBe s"MG ${(1 to 8).map(n => s"@AN[$n]").mkString(",")}"
  }

  test("AI poll: unparseable response leaves channel at 0, others still populated") {
    // Channel 4 returns garbage — should not affect channels 1-3 or 5-8
    val voltages = Map(1->"1.0000", 2->"2.0000", 3->"3.0000", 4->"NOT_A_FLOAT",
                       5->"5.0000", 6->"6.0000", 7->"7.0000", 8->"8.0000")
    val (sm, is) = createMonitorWithMock(aiMockBehavior(voltages))
    sm ! StatusMonitor.PollAnalogInputs
    Thread.sleep(200)
    val state = getHcdState(is)
    state.analogInputs(0) shouldBe (1.0f +- 0.001f)
    state.analogInputs(1) shouldBe (2.0f +- 0.001f)
    state.analogInputs(2) shouldBe (3.0f +- 0.001f)
    state.analogInputs(3) shouldBe (0.0f +- 0.001f)  // failed parse → 0
    state.analogInputs(4) shouldBe (5.0f +- 0.001f)
    state.analogInputs(5) shouldBe (6.0f +- 0.001f)
    state.analogInputs(6) shouldBe (7.0f +- 0.001f)
    state.analogInputs(7) shouldBe (8.0f +- 0.001f)
  }

  test("AI poll: all channels at 2.5V (simulator baseline)") {
    // Matches what the simulator returns for all @AN[n]
    val voltages = (1 to 8).map(_ -> "2.5000").toMap
    val (sm, is) = createMonitorWithMock(aiMockBehavior(voltages))
    sm ! StatusMonitor.PollAnalogInputs
    Thread.sleep(200)
    val state = getHcdState(is)
    state.analogInputs.foreach { v =>
      v shouldBe (2.5f +- 0.001f)
    }
  }