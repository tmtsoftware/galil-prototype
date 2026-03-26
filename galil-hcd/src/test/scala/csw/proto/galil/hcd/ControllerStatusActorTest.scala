package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import csw.logging.client.scaladsl.LoggerFactory
import csw.prefix.models.Prefix
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState, Header}

import scala.concurrent.duration._

/**
 * Tests for ControllerStatusActor Actor
 */
class ControllerStatusActorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:
  
  private val testKit = ActorTestKit()
  private val loggerFactory = new LoggerFactory(Prefix("APS.ICS.HCD.GalilMotion"))

  /** No-op GalilIo stub — used when tests inject QRResponse/QRError directly. */
  private def noOpIo: csw.proto.galil.io.GalilIo = new csw.proto.galil.io.GalilIo {
    import org.apache.pekko.util.ByteString
    override protected def write(sendBuf: Array[Byte]): Unit = ()
    override protected def read(): ByteString = ByteString(":")
    override def drainAndShowBuffer(timeoutMs: Int = 200): String = ""
    override def close(): Unit = ()
  }
  
  override def afterAll(): Unit =
    testKit.shutdownTestKit()
  
  private def createTestDataRecord(
    motorPositionA: Int = 0,
    velocityA: Int = 0,
    motorPositionB: Int = 0,
    velocityB: Int = 0
  ): DataRecord =
    val header = Header(List("A", "B"))
    val generalState = GeneralState(
      sampleNumber = 12345,
      inputs = Array.fill(10)(0.toByte),
      outputs = Array.fill(10)(0.toByte),
      ethernetHandleStatus = Array.fill(8)(0.toByte),
      errorCode = 0,
      threadStatus = 0,
      amplifierStatus = 0,
      contourModeSegmentCount = 0,
      contourModeBufferSpaceRemaining = 0,
      sPlaneSegmentCount = 0,
      sPlaneMoveStatus = 0,
      sPlaneDistanceTraveled = 0,
      sPlaneBufferSpaceRemaining = 0,
      tPlaneSegmentCount = 0,
      tPlaneMoveStatus = 0,
      tPlaneDistanceTraveled = 0,
      tPlaneBufferSpaceRemaining = 0
    )
    val axisA = GalilAxisStatus(motorPosition = motorPositionA, velocity = velocityA)
    val axisB = GalilAxisStatus(motorPosition = motorPositionB, velocity = velocityB)
    DataRecord(header, generalState, Array(axisA, axisB))

  private def createExtendedDataRecord(
    statusA: Short = 0,
    motorPositionA: Int = 0,
    velocityA: Int = 0,
    switchesA: Byte = 0,
    stopCodeA: Byte = 0,
    positionErrorA: Int = 0,
    statusB: Short = 0,
    motorPositionB: Int = 0,
    velocityB: Int = 0,
    switchesB: Byte = 0,
    stopCodeB: Byte = 0,
    positionErrorB: Int = 0,
    threadStatus: Byte = 0
  ): DataRecord =
    val header = Header(List("A", "B"))
    val generalState = GeneralState(
      sampleNumber = 12345,
      inputs = Array.fill(10)(0.toByte),
      outputs = Array.fill(10)(0.toByte),
      ethernetHandleStatus = Array.fill(8)(0.toByte),
      errorCode = 0,
      threadStatus = threadStatus,
      amplifierStatus = 0,
      contourModeSegmentCount = 0,
      contourModeBufferSpaceRemaining = 0,
      sPlaneSegmentCount = 0,
      sPlaneMoveStatus = 0,
      sPlaneDistanceTraveled = 0,
      sPlaneBufferSpaceRemaining = 0,
      tPlaneSegmentCount = 0,
      tPlaneMoveStatus = 0,
      tPlaneDistanceTraveled = 0,
      tPlaneBufferSpaceRemaining = 0
    )
    val axisA = GalilAxisStatus(
      status = statusA, motorPosition = motorPositionA, velocity = velocityA,
      switches = switchesA, stopCode = stopCodeA, positionError = positionErrorA)
    val axisB = GalilAxisStatus(
      status = statusB, motorPosition = motorPositionB, velocity = velocityB,
      switches = switchesB, stopCode = stopCodeB, positionError = positionErrorB)
    DataRecord(header, generalState, Array(axisA, axisB))

  test("ControllerStatusActor should update InternalState from QR response") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val dataRecord = createTestDataRecord(
      motorPositionA = 123456, velocityA = 5000 * 64,
      motorPositionB = 789012, velocityB = -3000 * 64
    )
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val probeA = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, probeA.ref)
    val stateA = probeA.receiveMessage()
    stateA should not be None
    stateA.get.position should be (123456.0)
    stateA.get.velocity should be (5000.0)
    val probeB = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.B, probeB.ref)
    val stateB = probeB.receiveMessage()
    stateB should not be None
    stateB.get.position should be (789012.0)
    stateB.get.velocity should be (-3000.0)
  }
  
  test("ControllerStatusActor should handle QR errors gracefully") {
    val internalState = testKit.spawn(InternalStateActor())
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    statusMonitor ! ControllerStatusActor.QRError("Communication timeout")
    val statusProbe = testKit.createTestProbe[ControllerStatusActor.PollingStatus]()
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    val status = statusProbe.receiveMessage()
    status.errorCount should be (1)
    statusMonitor ! ControllerStatusActor.QRError("Buffer overflow")
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.errorCount should be (2)
    val dataRecord = createTestDataRecord()
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(50)
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    val status3 = statusProbe.receiveMessage()
    status3.errorCount should be (0)
  }
  
  test("ControllerStatusActor should support enabling/disabling polling") {
    val internalState = testKit.spawn(InternalStateActor())
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val statusProbe = testKit.createTestProbe[ControllerStatusActor.PollingStatus]()
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().enabled should be (true)
    statusMonitor ! ControllerStatusActor.SetPolling(false)
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().enabled should be (false)
    statusMonitor ! ControllerStatusActor.SetPolling(true)
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().enabled should be (true)
  }
  
  test("ControllerStatusActor should support changing polling rate") {
    val internalState = testKit.spawn(InternalStateActor())
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val statusProbe = testKit.createTestProbe[ControllerStatusActor.PollingStatus]()
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be (10.0)
    statusMonitor ! ControllerStatusActor.SetPollingRate(20.0)
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be (20.0)
    statusMonitor ! ControllerStatusActor.SetPollingRate(5.0)
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be (5.0)
  }
  
  test("ControllerStatusActor should update lastPollTime on successful QR") {
    val internalState = testKit.spawn(InternalStateActor())
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val statusProbe = testKit.createTestProbe[ControllerStatusActor.PollingStatus]()
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().lastPollTime should be (None)
    val dataRecord = createTestDataRecord()
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(50)
    statusMonitor ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.lastPollTime should not be None
    status2.lastPollTime.get should be > 0L
  }
  
  test("ControllerStatusActor should parse multiple axes from QR") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B).initializeAxis(Axis.C).initializeAxis(Axis.D)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val header = Header(List("A", "B", "C", "D"))
    val generalState = GeneralState(
      sampleNumber = 1, inputs = Array.fill(10)(0.toByte), outputs = Array.fill(10)(0.toByte),
      ethernetHandleStatus = Array.fill(8)(0.toByte), errorCode = 0, threadStatus = 0,
      amplifierStatus = 0, contourModeSegmentCount = 0, contourModeBufferSpaceRemaining = 0,
      sPlaneSegmentCount = 0, sPlaneMoveStatus = 0, sPlaneDistanceTraveled = 0,
      sPlaneBufferSpaceRemaining = 0, tPlaneSegmentCount = 0, tPlaneMoveStatus = 0,
      tPlaneDistanceTraveled = 0, tPlaneBufferSpaceRemaining = 0
    )
    val axisStatuses = Array(
      GalilAxisStatus(motorPosition = 1000, velocity = 100 * 64),
      GalilAxisStatus(motorPosition = 2000, velocity = 200 * 64),
      GalilAxisStatus(motorPosition = 3000, velocity = 300 * 64),
      GalilAxisStatus(motorPosition = 4000, velocity = 400 * 64)
    )
    val dataRecord = DataRecord(header, generalState, axisStatuses)
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val axes = List(Axis.A, Axis.B, Axis.C, Axis.D)
    val expectedPositions = List(1000.0, 2000.0, 3000.0, 4000.0)
    val expectedVelocities = List(100.0, 200.0, 300.0, 400.0)
    axes.zip(expectedPositions).zip(expectedVelocities).foreach {
      case ((axis, expectedPos), expectedVel) =>
        val probe = testKit.createTestProbe[Option[AxisState]]()
        internalState ! InternalStateActor.GetAxisState(axis, probe.ref)
        val state = probe.receiveMessage()
        state should not be None
        state.get.position should be (expectedPos)
        state.get.velocity should be (expectedVel)
    }
  }
  
  test("ControllerStatusActor should update InternalState from injected QR data") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val dataRecord = createTestDataRecord(motorPositionA = 98765, velocityA = 1234 * 64)
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val probe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    val state = probe.receiveMessage()
    state should not be None
    state.get.position should be (98765.0)
    state.get.velocity should be (1234.0)
  }

  test("ControllerStatusActor should decode named switch fields from QR response") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val switchesA: Byte = (0x08 | 0x02).toByte  // forwardLimit + homeInput
    val switchesB: Byte = 0x04.toByte            // reverseLimit only
    val dataRecord = createExtendedDataRecord(
      motorPositionA = 1000, switchesA = switchesA,
      motorPositionB = 2000, switchesB = switchesB,
      statusB = 0x0001.toShort  // motorOff in status word bit 0
    )
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val probeA = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, probeA.ref)
    val stateA = probeA.receiveMessage().get
    stateA.forwardLimit should be(true)
    stateA.reverseLimit should be(false)
    stateA.homeSwitch should be(true)
    stateA.isStepper should be(false)
    stateA.negativeDirection should be(false)
    stateA.motorOff should be(false)
    val probeB = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.B, probeB.ref)
    val stateB = probeB.receiveMessage().get
    stateB.forwardLimit should be(false)
    stateB.reverseLimit should be(true)
    stateB.homeSwitch should be(false)
    stateB.isStepper should be(false)
    stateB.negativeDirection should be(false)
    stateB.motorOff should be(true)
  }

  test("ControllerStatusActor should route moving and stopCode to AxisCmdState") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    val dataRecord = createExtendedDataRecord(
      statusA = 0x8000.toShort, stopCodeA = 0,
      statusB = 0x0000.toShort, stopCodeB = 1
    )
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val probeA = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA.ref)
    val cmdA = probeA.receiveMessage().get
    cmdA.moving should be(true)
    cmdA.stopCode should be(0)
    val probeB = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.B, probeB.ref)
    val cmdB = probeB.receiveMessage().get
    cmdB.moving should be(false)
    cmdB.stopCode should be(1)
  }

  // These tests verify that QR threadStatus bitmask changes are correctly reflected in
  // AxisCmdState.activeThread via the registry path. RegisterThread must be called first
  // (as CommandHandlerActor does after XQ) to establish the axis↔thread mapping; the
  // registry is the only authoritative source since thread assignment is dynamic.

  test("ControllerStatusActor should decode threadStatus into per-axis activeThread") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    // Register axis A on thread 1 (as CommandHandlerActor does after XQ #HomeA,1)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // QR reports thread 1 active (bit 1 = 0x02)
    val dataRecord = createExtendedDataRecord(threadStatus = 0x02.toByte)
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val probeA = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA.ref)
    probeA.receiveMessage().get.activeThread should be(1)
    val probeB = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.B, probeB.ref)
    probeB.receiveMessage().get.activeThread should be(0)
  }

  test("ControllerStatusActor should decode multiple active threads") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    // Register A on thread 1, B on thread 2 (as CommandHandlerActor does after XQ)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    internalState ! InternalStateActor.RegisterThread(2, Axis.B)
    Thread.sleep(20)
    // QR reports both threads active (bits 1+2 = 0x06)
    val dataRecord = createExtendedDataRecord(threadStatus = 0x06.toByte)
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val probeA = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA.ref)
    probeA.receiveMessage().get.activeThread should be(1)
    val probeB = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.B, probeB.ref)
    probeB.receiveMessage().get.activeThread should be(2)
  }

  test("ControllerStatusActor should set activeThread to 0 when thread stops") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    // Register axis A on thread 1, then simulate QR with thread active
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    val dr1 = createExtendedDataRecord(threadStatus = 0x02.toByte)
    statusMonitor ! ControllerStatusActor.QRResponse(dr1)
    Thread.sleep(100)
    val probeA1 = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA1.ref)
    probeA1.receiveMessage().get.activeThread should be(1)
    // Now thread 1 stops — registry clears it and sets activeThread=0
    val dr2 = createExtendedDataRecord(threadStatus = 0x00.toByte)
    statusMonitor ! ControllerStatusActor.QRResponse(dr2)
    Thread.sleep(100)
    val probeA2 = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA2.ref)
    probeA2.receiveMessage().get.activeThread should be(0)
  }

  test("ControllerStatusActor QR should update both AxisState and AxisCmdState") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A)
    ))
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    // Register axis A on thread 1 before sending QR data
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    val switchByte: Byte = 0x00.toByte
    val statusWord: Short = (0x8000 | 0x0080 | 0x0001).toShort
    val dataRecord = createExtendedDataRecord(
      statusA = statusWord, motorPositionA = 50000, velocityA = 25000 * 64,
      positionErrorA = 5, switchesA = switchByte, stopCodeA = 0,
      threadStatus = 0x02.toByte
    )
    statusMonitor ! ControllerStatusActor.QRResponse(dataRecord)
    Thread.sleep(100)
    val axisProbe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, axisProbe.ref)
    val axisState = axisProbe.receiveMessage().get
    axisState.position should be(50000.0)
    axisState.velocity should be(25000.0)
    axisState.positionError should be(5.0)
    axisState.negativeDirection should be(true)
    axisState.motorOff should be(true)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    val cmdState = cmdProbe.receiveMessage().get
    cmdState.moving should be(true)
    cmdState.stopCode should be(0)
    cmdState.activeThread should be(1)
  }
  
  test("ControllerStatusActor should switch to action rate when axis state becomes active") {
    val internalState = testKit.spawn(InternalStateActor(), "is-adaptive-rate")
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory,
        standbyPollingRateHz = 1.0, actionPollingRateHz = 10.0)
    )
    val statusProbe = testKit.createTestProbe[ControllerStatusActor.PollingStatus]()
    sm ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(1.0)
    val replyProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("axisState" -> AxisStateEnum.Moving), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    sm ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(10.0)
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("axisState" -> AxisStateEnum.Idle), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    sm ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(1.0)
  }
  
  test("ControllerStatusActor should stay at action rate while any axis is active") {
    val internalState = testKit.spawn(InternalStateActor(), "is-multi-axis-rate")
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(noOpIo, internalState, loggerFactory,
        standbyPollingRateHz = 1.0, actionPollingRateHz = 10.0)
    )
    val statusProbe = testKit.createTestProbe[ControllerStatusActor.PollingStatus]()
    val replyProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("axisState" -> AxisStateEnum.Moving), replyProbe.ref)
    replyProbe.receiveMessage()
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.B, Map("axisState" -> AxisStateEnum.Homing), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    sm ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(10.0)
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("axisState" -> AxisStateEnum.Idle), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    sm ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(10.0)  // B still active
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.B, Map("axisState" -> AxisStateEnum.Idle), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    sm ! ControllerStatusActor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(1.0)  // Both idle now
  }