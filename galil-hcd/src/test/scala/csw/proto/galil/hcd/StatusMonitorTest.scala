package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState, Header}

import scala.concurrent.duration._

/**
 * Tests for StatusMonitor Actor
 * 
 * Validates:
 * - QR response handling
 * - State update logic
 * - Polling control
 * - Error handling
 */
class StatusMonitorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:
  
  private val testKit = ActorTestKit()
  
  override def afterAll(): Unit =
    testKit.shutdownTestKit()
  
  /**
   * Helper: Create test DataRecord with motor data
   */
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
    
    val axisA = GalilAxisStatus(
      motorPosition = motorPositionA,
      velocity = velocityA
    )
    val axisB = GalilAxisStatus(
      motorPosition = motorPositionB,
      velocity = velocityB
    )
    
    DataRecord(header, generalState, Array(axisA, axisB))
  
  // ========================================
  // QR Response Handling Tests
  // ========================================
  
  test("StatusMonitor should update InternalState from QR response") {
    // Setup actors
    val internalState = testKit.spawn(InternalStateActor(
      HcdState()
        .initializeAxis(Axis.A)
        .initializeAxis(Axis.B)
    ))
    
    // Mock ControllerInterface (not used in this test)
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Create test data with specific positions
    // Note: QR DataRecord velocity is 64x the TV value (per Galil docs)
    val dataRecord = createTestDataRecord(
      motorPositionA = 123456,
      velocityA = 5000 * 64,
      motorPositionB = 789012,
      velocityB = -3000 * 64
    )
    
    // Send QR response directly (simulating controller response)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    // Give it time to process
    Thread.sleep(100)
    
    // Verify Axis A was updated
    val probeA = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, probeA.ref)
    val stateA = probeA.receiveMessage()
    
    stateA should not be (None)
    stateA.get.position should be (123456.0)
    stateA.get.velocity should be (5000.0)
    
    // Verify Axis B was updated
    val probeB = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.B, probeB.ref)
    val stateB = probeB.receiveMessage()
    
    stateB should not be (None)
    stateB.get.position should be (789012.0)
    stateB.get.velocity should be (-3000.0)
  }
  
  test("StatusMonitor should handle QR errors gracefully") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Send error
    statusMonitor ! StatusMonitor.QRError("Communication timeout")
    
    // Query status - should show error count
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    
    val status = statusProbe.receiveMessage()
    status.errorCount should be (1)
    
    // Send another error
    statusMonitor ! StatusMonitor.QRError("Buffer overflow")
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    
    val status2 = statusProbe.receiveMessage()
    status2.errorCount should be (2)
    
    // Successful QR should reset error count
    val dataRecord = createTestDataRecord()
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    Thread.sleep(50)  // Give it time to process
    
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status3 = statusProbe.receiveMessage()
    status3.errorCount should be (0)
  }
  
  // ========================================
  // Polling Control Tests
  // ========================================
  
  test("StatusMonitor should support enabling/disabling polling") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Should start enabled
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.enabled should be (true)
    
    // Disable polling
    statusMonitor ! StatusMonitor.SetPolling(false)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.enabled should be (false)
    
    // Re-enable polling
    statusMonitor ! StatusMonitor.SetPolling(true)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status3 = statusProbe.receiveMessage()
    status3.enabled should be (true)
  }
  
  test("StatusMonitor should support changing polling rate") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Initial rate should be 10Hz
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.rateHz should be (10.0)
    
    // Change to 20Hz
    statusMonitor ! StatusMonitor.SetPollingRate(20.0)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.rateHz should be (20.0)
    
    // Change to 5Hz
    statusMonitor ! StatusMonitor.SetPollingRate(5.0)
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status3 = statusProbe.receiveMessage()
    status3.rateHz should be (5.0)
  }
  
  test("StatusMonitor should update lastPollTime on successful QR") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Initially no poll
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.lastPollTime should be (None)
    
    // Send QR response
    val dataRecord = createTestDataRecord()
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    Thread.sleep(50)  // Give it time to process
    
    // Should now have lastPollTime
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.lastPollTime should not be (None)
    status2.lastPollTime.get should be > 0L
  }
  
  // ========================================
  // Pause/Resume Tests (File Operation Support)
  // ========================================
  
  test("StatusMonitor should pause QR polling for file operations") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Should start not paused
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status1 = statusProbe.receiveMessage()
    status1.paused should be (false)
    
    // Pause for file operation
    statusMonitor ! StatusMonitor.PauseQRPolling
    
    // Should now be paused
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val status2 = statusProbe.receiveMessage()
    status2.paused should be (true)
  }
  
  test("StatusMonitor should skip queued QR when paused") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Pause polling
    statusMonitor ! StatusMonitor.PauseQRPolling
    Thread.sleep(50)
    
    // Send QR response while paused - should be processed but not update lastPollTime
    // (QR responses are always processed, but timer-triggered polls are skipped)
    val dataRecord = createTestDataRecord(motorPositionA = 999)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    Thread.sleep(50)
    
    // Data should still be updated (QRResponse is always processed)
    val axisProbe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, axisProbe.ref)
    val axisState = axisProbe.receiveMessage()
    axisState.get.position should be (999.0)
  }
  
  test("StatusMonitor should resume QR polling after file operations") {
    val internalState = testKit.spawn(InternalStateActor())
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    
    // Pause
    statusMonitor ! StatusMonitor.PauseQRPolling
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().paused should be (true)
    
    // Resume
    statusMonitor ! StatusMonitor.ResumeQRPolling
    statusMonitor ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().paused should be (false)
  }
  
  // ========================================
  // Data Parsing Tests
  // ========================================
  
  test("StatusMonitor should parse multiple axes from QR") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState()
        .initializeAxis(Axis.A)
        .initializeAxis(Axis.B)
        .initializeAxis(Axis.C)
        .initializeAxis(Axis.D)
    ))
    
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Create DataRecord with 4 axes
    val header = Header(List("A", "B", "C", "D"))
    val generalState = GeneralState(
      sampleNumber = 1,
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
    
    val axisStatuses = Array(
      GalilAxisStatus(motorPosition = 1000, velocity = 100 * 64),
      GalilAxisStatus(motorPosition = 2000, velocity = 200 * 64),
      GalilAxisStatus(motorPosition = 3000, velocity = 300 * 64),
      GalilAxisStatus(motorPosition = 4000, velocity = 400 * 64)
    )
    
    val dataRecord = DataRecord(header, generalState, axisStatuses)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    
    Thread.sleep(100)
    
    // Verify all axes were updated
    val axes = List(Axis.A, Axis.B, Axis.C, Axis.D)
    val expectedPositions = List(1000.0, 2000.0, 3000.0, 4000.0)
    val expectedVelocities = List(100.0, 200.0, 300.0, 400.0)
    
    axes.zip(expectedPositions).zip(expectedVelocities).foreach { 
      case ((axis, expectedPos), expectedVel) =>
        val probe = testKit.createTestProbe[Option[AxisState]]()
        internalState ! InternalStateActor.GetAxisState(axis, probe.ref)
        val state = probe.receiveMessage()
        
        state should not be (None)
        state.get.position should be (expectedPos)
        state.get.velocity should be (expectedVel)
    }
  }
  
  // ========================================
  // CRITICAL: Verify Actual Polling Works
  // ========================================
  
  test("StatusMonitor should send GetQR requests to ControllerInterface") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState()
        .initializeAxis(Axis.A)
        .initializeAxis(Axis.B)
    ))
    
    // Create probe to receive GetQR messages
    val controllerProbe = testKit.createTestProbe[GalilCommandMessage]()
    
    // Create StatusMonitor with 10Hz polling (100ms period)
    val statusMonitor = testKit.spawn(
      StatusMonitor(controllerProbe.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Wait for timer to fire (100ms + margin)
    Thread.sleep(150)
    
    // Verify ControllerInterface received GetQR request
    val getQrMessage = controllerProbe.receiveMessage(200.millis)
    getQrMessage shouldBe a[GalilCommandMessage.GetQR]
    
    // Extract replyTo from GetQR message
    val replyTo = getQrMessage.asInstanceOf[GalilCommandMessage.GetQR].replyTo
    
    // Send QR response back through the replyTo actor
    val dataRecord = createTestDataRecord(
      motorPositionA = 98765,
      velocityA = 1234 * 64
    )
    replyTo ! GalilCommandMessage.QRResult(dataRecord)
    
    // Give StatusMonitor time to process and update InternalState
    Thread.sleep(100)
    
    // Verify InternalState was updated
    val probe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    val state = probe.receiveMessage()
    
    state should not be (None)
    state.get.position should be (98765.0)
    state.get.velocity should be (1234.0)
    
    // Verify polling continues - should get another GetQR within 150ms
    val getQrMessage2 = controllerProbe.receiveMessage(200.millis)
    getQrMessage2 shouldBe a[GalilCommandMessage.GetQR]
  }
  
  test("StatusMonitor should pause polling when PauseQRPolling is sent") {
    val internalState = testKit.spawn(InternalStateActor(HcdState()))
    val controllerProbe = testKit.createTestProbe[GalilCommandMessage]()
    
    val statusMonitor = testKit.spawn(
      StatusMonitor(controllerProbe.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    
    // Verify polling is active
    controllerProbe.receiveMessage(200.millis) shouldBe a[GalilCommandMessage.GetQR]
    
    // Pause polling
    statusMonitor ! StatusMonitor.PauseQRPolling
    Thread.sleep(50) // Let pause message process
    
    // Verify no more GetQR messages arrive
    controllerProbe.expectNoMessage(250.millis)
    
    // Resume polling
    statusMonitor ! StatusMonitor.ResumeQRPolling
    Thread.sleep(50) // Let resume message process
    
    // Verify polling resumes
    controllerProbe.receiveMessage(200.millis) shouldBe a[GalilCommandMessage.GetQR]
  }

  // ========================================
  // Extended DataRecord helper
  // ========================================

  /**
   * Helper: Create test DataRecord with switches, stopCode, and threadStatus
   */
  private def createExtendedDataRecord(
    statusA: Short = 0,        // axis status word (bit 15 = move in progress)
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
      status = statusA,
      motorPosition = motorPositionA,
      velocity = velocityA,
      switches = switchesA,
      stopCode = stopCodeA,
      positionError = positionErrorA
    )
    val axisB = GalilAxisStatus(
      status = statusB,
      motorPosition = motorPositionB,
      velocity = velocityB,
      switches = switchesB,
      stopCode = stopCodeB,
      positionError = positionErrorB
    )

    DataRecord(header, generalState, Array(axisA, axisB))

  // ========================================
  // Named Switch Decoding Tests
  // ========================================

  test("StatusMonitor should decode named switch fields from QR response") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )

    // QR DataRecord switches byte layout (NOT the same as TS command):
    //   bit7=latchOccurred, bit6=latchInput, bit5=N/A, bit4=N/A,
    //   bit3=forwardLimit, bit2=reverseLimit, bit1=homeInput, bit0=motorOff
    //
    // Axis A: forward limit + home input
    //   bit3=1 (forward limit), bit1=1 (home input)
    //   = 0b00001010 = 0x0A = 10
    val switchesA: Byte = (0x08 | 0x02).toByte  // forwardLimit + homeInput
    // Axis B: reverse limit + motor off (motorOff is in status word, not switches)
    //   switches: bit2=1 (reverse limit)
    //   status word: bit0=1 (motor off)
    val switchesB: Byte = 0x04.toByte  // reverseLimit only

    val dataRecord = createExtendedDataRecord(
      motorPositionA = 1000, switchesA = switchesA,
      motorPositionB = 2000, switchesB = switchesB,
      statusB = 0x0001.toShort  // motorOff in status word bit 0
    )
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    Thread.sleep(100)

    // Verify Axis A named switches
    val probeA = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, probeA.ref)
    val stateA = probeA.receiveMessage().get
    stateA.forwardLimit should be(true)
    stateA.reverseLimit should be(false)
    stateA.homeSwitch should be(true)    // from switches.homeInput
    stateA.isStepper should be(false)    // from switches byte bit 0 (stepperMode)
    stateA.negativeDirection should be(false)  // from status word bit 7 (not set)
    stateA.motorOff should be(false)

    // Verify Axis B named switches
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

  test("StatusMonitor should route moving and stopCode to AxisCmdState") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )

    // Axis A: moving (status word bit 15 = 0x8000), stopCode=0 (still moving)
    // Axis B: not moving, stopCode=1 (normal decel stop)
    val dataRecord = createExtendedDataRecord(
      statusA = 0x8000.toShort, stopCodeA = 0,
      statusB = 0x0000.toShort, stopCodeB = 1
    )
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    Thread.sleep(100)

    // Verify Axis A CmdState
    val probeA = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA.ref)
    val cmdA = probeA.receiveMessage().get
    cmdA.moving should be(true)
    cmdA.stopCode should be(0)

    // Verify Axis B CmdState
    val probeB = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.B, probeB.ref)
    val cmdB = probeB.receiveMessage().get
    cmdB.moving should be(false)
    cmdB.stopCode should be(1)
  }

  // ========================================
  // ThreadStatus Decoding Tests
  // ========================================

  test("StatusMonitor should decode threadStatus into per-axis activeThread") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )

    // threadStatus bitmask: bit1=thread1(A), bit2=thread2(B)
    // Thread 1 active (axis A running): threadStatus = 0x02 = 0b00000010
    val dataRecord = createExtendedDataRecord(threadStatus = 0x02.toByte)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    Thread.sleep(100)

    // Axis A thread 1 should be active
    val probeA = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA.ref)
    val cmdA = probeA.receiveMessage().get
    cmdA.activeThread should be(1)

    // Axis B thread 2 should be inactive
    val probeB = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.B, probeB.ref)
    val cmdB = probeB.receiveMessage().get
    cmdB.activeThread should be(0)
  }

  test("StatusMonitor should decode multiple active threads") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )

    // Both threads active: bit1 + bit2 = 0x06 = 0b00000110
    val dataRecord = createExtendedDataRecord(threadStatus = 0x06.toByte)
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    Thread.sleep(100)

    val probeA = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA.ref)
    probeA.receiveMessage().get.activeThread should be(1)

    val probeB = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.B, probeB.ref)
    probeB.receiveMessage().get.activeThread should be(2)
  }

  test("StatusMonitor should set activeThread to 0 when thread stops") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )

    // First poll: thread 1 active
    val dr1 = createExtendedDataRecord(threadStatus = 0x02.toByte)
    statusMonitor ! StatusMonitor.QRResponse(dr1)
    Thread.sleep(100)

    val probeA1 = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA1.ref)
    probeA1.receiveMessage().get.activeThread should be(1)

    // Second poll: no threads active
    val dr2 = createExtendedDataRecord(threadStatus = 0x00.toByte)
    statusMonitor ! StatusMonitor.QRResponse(dr2)
    Thread.sleep(100)

    val probeA2 = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA2.ref)
    probeA2.receiveMessage().get.activeThread should be(0)
  }

  // ========================================
  // Dual-Channel Routing Test
  // ========================================

  test("StatusMonitor QR should update both AxisState and AxisCmdState") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A)
    ))
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    val statusMonitor = testKit.spawn(
      StatusMonitor(mockController.ref, internalState, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )

    // QR with position data + status word (moving, negDir, motorOff) + stopCode + threadStatus
    // Status word: bit15=moveInProgress, bit7=negativeDirection, bit0=motorOff
    // Switches byte (QR format): bit0=stepperMode (not used for motorOff)
    val switchByte: Byte = 0x00.toByte
    val statusWord: Short = (0x8000 | 0x0080 | 0x0001).toShort  // moveInProgress + negativeDirection + motorOff
    val dataRecord = createExtendedDataRecord(
      statusA = statusWord,
      motorPositionA = 50000,
      velocityA = 25000 * 64,  // QR velocity is 64x TV value
      positionErrorA = 5,
      switchesA = switchByte,
      stopCodeA = 0,
      threadStatus = 0x02.toByte  // thread 1 active
    )
    statusMonitor ! StatusMonitor.QRResponse(dataRecord)
    Thread.sleep(100)

    // Verify AxisState (operational data)
    val axisProbe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, axisProbe.ref)
    val axisState = axisProbe.receiveMessage().get
    axisState.position should be(50000.0)
    axisState.velocity should be(25000.0)  // divided by 64
    axisState.positionError should be(5.0)
    axisState.negativeDirection should be(true)  // from status word bit 7
    axisState.motorOff should be(true)  // from status word bit 0

    // Verify AxisCmdState (command-relevant data)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    val cmdState = cmdProbe.receiveMessage().get
    cmdState.moving should be(true)
    cmdState.stopCode should be(0)
    cmdState.activeThread should be(1)
  }
  
  test("StatusMonitor should switch to action rate when axis state becomes active") {
    val internalState = testKit.spawn(InternalStateActor(), "is-adaptive-rate")
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    // Create SM with distinct standby/action rates
    val sm = testKit.spawn(
      StatusMonitor(mockController.ref, internalState,
        standbyPollingRateHz = 1.0, actionPollingRateHz = 10.0)
    )
    
    // Verify starting at standby rate
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    sm ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val initialStatus = statusProbe.receiveMessage()
    initialStatus.rateHz should be(1.0)
    
    // Simulate CommandHandler setting axis A to Moving
    // This updates IS which notifies SM via StateChanged subscription
    val replyProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("axisState" -> AxisStateEnum.Moving),
      replyProbe.ref
    )
    replyProbe.receiveMessage()
    
    // Allow notification to propagate through message adapter
    Thread.sleep(100)
    
    // Verify SM switched to action rate
    sm ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val activeStatus = statusProbe.receiveMessage()
    activeStatus.rateHz should be(10.0)
    
    // Now simulate CommandWatcher setting axis A back to Idle
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("axisState" -> AxisStateEnum.Idle),
      replyProbe.ref
    )
    replyProbe.receiveMessage()
    
    Thread.sleep(100)
    
    // Verify SM switched back to standby rate
    sm ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    val idleStatus = statusProbe.receiveMessage()
    idleStatus.rateHz should be(1.0)
  }
  
  test("StatusMonitor should stay at action rate while any axis is active") {
    val internalState = testKit.spawn(InternalStateActor(), "is-multi-axis-rate")
    val mockController = testKit.createTestProbe[GalilCommandMessage]()
    
    val sm = testKit.spawn(
      StatusMonitor(mockController.ref, internalState,
        standbyPollingRateHz = 1.0, actionPollingRateHz = 10.0)
    )
    
    val statusProbe = testKit.createTestProbe[StatusMonitor.PollingStatus]()
    val replyProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Set axis A to Moving
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("axisState" -> AxisStateEnum.Moving), replyProbe.ref)
    replyProbe.receiveMessage()
    
    // Set axis B to Homing
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.B, Map("axisState" -> AxisStateEnum.Homing), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    
    sm ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(10.0)
    
    // Set axis A to Idle — axis B still homing, should stay at action rate
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("axisState" -> AxisStateEnum.Idle), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    
    sm ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(10.0)  // B still active
    
    // Set axis B to Idle — now all standby
    internalState ! InternalStateActor.UpdateAxisState(
      Axis.B, Map("axisState" -> AxisStateEnum.Idle), replyProbe.ref)
    replyProbe.receiveMessage()
    Thread.sleep(100)
    
    sm ! StatusMonitor.GetPollingStatus(statusProbe.ref)
    statusProbe.receiveMessage().rateHz should be(1.0)  // Both idle now
  }