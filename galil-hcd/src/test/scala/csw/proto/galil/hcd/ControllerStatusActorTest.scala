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

  /**
   * GalilIo stub for status-connection timeout tests.  On AI reads (MG @AN...) it
   * throws SocketTimeoutException or returns a valid reply per successive entries of
   * `throwSeq` (true = throw, false = reply); the iterator advances only on AI reads.
   * QR and any other command return ":" — harmless here (a QR parse failure bumps
   * errorCount but never touches the timeout counters or faults), so the QR poll
   * timer cannot interfere with the AI-timeout assertions.
   */
  private def aiTimeoutSeqIo(throwSeq: Seq[Boolean]): csw.proto.galil.io.GalilIo =
    new csw.proto.galil.io.GalilIo {
      import org.apache.pekko.util.ByteString
      private val seq = throwSeq.iterator
      private var lastWasAi = false
      private var aiShouldThrow = false
      // Decide throw-or-not once per command (the iterator advances only on AI
      // commands), so a reply read more than once by receiveReplies stays consistent.
      override protected def write(sendBuf: Array[Byte]): Unit =
        lastWasAi = new String(sendBuf).trim.startsWith("MG @AN")
        if lastWasAi then aiShouldThrow = seq.hasNext && seq.next()
      override protected def read(): ByteString =
        if !lastWasAi then ByteString(":")
        else if aiShouldThrow then throw new java.net.SocketTimeoutException("Read timed out")
        else ByteString(Seq.fill(8)("2.5000").mkString(" ") + "\r\n:")
      override def drainAndShowBuffer(timeoutMs: Int = 200): String = ""
      override def close(): Unit = ()
    }

  /**
   * GalilIo stub for QR malformed-record tests.  On QR reads it consumes `behaviors`:
   * "timeout" throws SocketTimeoutException; "malformed" returns a 4-byte record whose
   * declared size (0xFFFF) cannot match any block layout, so DataRecord.apply throws
   * DataRecordFormatException before any buffer underflow.  The decision is made once
   * per command and the iterator advances only on QR reads; AI/other return ":".
   */
  private def qrFaultSeqIo(behaviors: Seq[String]): csw.proto.galil.io.GalilIo =
    new csw.proto.galil.io.GalilIo {
      import org.apache.pekko.util.ByteString
      private val seq = behaviors.iterator
      private var lastWasQr = false
      private var pending = "malformed"
      override protected def write(sendBuf: Array[Byte]): Unit =
        lastWasQr = new String(sendBuf).trim == "QR"
        if lastWasQr then pending = if seq.hasNext then seq.next() else "malformed"
      override protected def read(): ByteString =
        if !lastWasQr then ByteString(":")
        else if pending == "timeout" then throw new java.net.SocketTimeoutException("Read timed out")
        // 0xFFFF declared size + ':' terminator: receiveReplies completes (a real socket
        // would otherwise hit its read timeout), then DataRecord throws DataRecordFormatException.
        else ByteString(Array[Byte](0, 0, -1, -1, ':'.toByte))
      override def drainAndShowBuffer(timeoutMs: Int = 200): String = ""
      override def close(): Unit = ()
    }

  /**
   * Test IO stub that simulates `MG _XQ<n>` and `MG ae[i]` responses based on
   * a mutable set of "live threads" the test controls. Used by tests that
   * inject QRResponse and need CS's per-scan _XQ query to return sensible
   * values matching the test scenario.
   *
   * Usage:
   *   val liveThreads = scala.collection.mutable.Set[Int](1)  // thread 1 active
   *   val io = stubIoWithLiveThreads(liveThreads)
   *   ... send QRResponse ...
   *   liveThreads -= 1  // simulate thread completion
   *   ... send another QRResponse ...
   *
   * Other MG queries (and any non-MG send) return ":" like noOpIo.
   */
  private def stubIoWithLiveThreads(liveThreads: scala.collection.mutable.Set[Int]): csw.proto.galil.io.GalilIo =
    new csw.proto.galil.io.GalilIo {
      import org.apache.pekko.util.ByteString
      private var pendingResponse: ByteString = ByteString(":")

      override protected def write(sendBuf: Array[Byte]): Unit = {
        val cmd = new String(sendBuf).trim
        // Recognize compound MG _XQ<n>,_XQ<m>,... — return space-separated
        // 1.0000 (running) / -1.0000 (stopped) per liveThreads.
        if (cmd.startsWith("MG _XQ")) {
          val args = cmd.drop(3).split(',').map(_.trim)
          val values = args.map { arg =>
            if (arg.startsWith("_XQ") && arg.length > 3)
              scala.util.Try(arg.drop(3).toInt).toOption match
                case Some(n) => if (liveThreads.contains(n)) "1.0000" else "-1.0000"
                case None    => "-1.0000"
            else "0.0000"
          }
          pendingResponse = ByteString(values.mkString(" ") + "\r\n:")
        } else if (cmd.startsWith("MG ae[")) {
          // Return 0.0000 for each ae[] arg (the unit tests don't exercise
          // attribution paths that depend on ae values; they verify thread
          // bookkeeping. If a test needs richer ae behavior, extend this stub).
          val args = cmd.drop(3).split(',').map(_.trim)
          pendingResponse = ByteString(Seq.fill(args.length)("0.0000").mkString(" ") + "\r\n:")
        } else {
          pendingResponse = ByteString(":")
        }
      }
      override protected def read(): ByteString = pendingResponse
      override def drainAndShowBuffer(timeoutMs: Int = 200): String = ""
      override def close(): Unit = ()
    }

  /**
   * Like `stubIoWithLiveThreads` but also handles `MG ae[i]` queries from a
   * caller-controlled map keyed by axis index (0=A, 1=B, ..., 7=H).  Unmapped
   * axes return ae=0 (no error).  Also handles `TC 1` queries by returning a
   * caller-controlled text — used by the controller-error attribution path.
   *
   * Usage:
   *   val liveThreads = scala.collection.mutable.Set[Int](1)
   *   val ae = scala.collection.mutable.Map[Int, Int](0 -> 1)  // axis A: program error
   *   val io = stubIoWithThreadsAndAe(liveThreads, ae)
   *   ... send QRResponse with errorCode=1, threadStatus 0x02 ...
   *   liveThreads -= 1  // thread 1 stops → axis A error attributed
   *   ... send next QRResponse with errorCode=1, threadStatus 0x00 ...
   *
   * The tcMessage parameter is the body of the response to `TC 1` (CS calls
   * this when it consumes the controller error latch).  Default mimics the
   * STB error "1 Unrecognized command".
   */
  private def stubIoWithThreadsAndAe(
    liveThreads: scala.collection.mutable.Set[Int],
    aeValues:    scala.collection.mutable.Map[Int, Int],
    tcMessage:   String = "1 Unrecognized command"
  ): csw.proto.galil.io.GalilIo =
    new csw.proto.galil.io.GalilIo {
      import org.apache.pekko.util.ByteString
      private var pendingResponse: ByteString = ByteString(":")
      override protected def write(sendBuf: Array[Byte]): Unit = {
        val cmd = new String(sendBuf).trim
        if (cmd.startsWith("MG _XQ")) {
          val args = cmd.drop(3).split(',').map(_.trim)
          val values = args.map { arg =>
            if (arg.startsWith("_XQ") && arg.length > 3)
              scala.util.Try(arg.drop(3).toInt).toOption match
                case Some(n) => if (liveThreads.contains(n)) "1.0000" else "-1.0000"
                case None    => "-1.0000"
            else "0.0000"
          }
          pendingResponse = ByteString(values.mkString(" ") + "\r\n:")
        } else if (cmd.startsWith("MG ae[")) {
          val args = cmd.drop(3).split(',').map(_.trim)
          val values = args.map { arg =>
            // Parse "ae[N]" → N (axis index)
            val idx = arg.dropWhile(_ != '[').drop(1).takeWhile(_ != ']')
            scala.util.Try(idx.toInt).toOption match
              case Some(n) => f"${aeValues.getOrElse(n, 0).toDouble}%.4f"
              case None    => "0.0000"
          }
          pendingResponse = ByteString(values.mkString(" ") + "\r\n:")
        } else if (cmd.startsWith("TC")) {
          // Controller error latch fetch.  Returns the caller-supplied text
          // followed by the standard ":" ack.
          pendingResponse = ByteString(s"$tcMessage\r\n:")
        } else {
          pendingResponse = ByteString(":")
        }
      }
      override protected def read(): ByteString = pendingResponse
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

  test("ControllerStatusActor tolerates a single AI read timeout, faults on the second") {
    val internalState = testKit.spawn(InternalStateActor())
    // Low QR rate so the QR poll timer does not fire during this sub-second test;
    // the AI poll timer first fires at 1s, after the assertions complete.
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(aiTimeoutSeqIo(Seq(true, true)), internalState,
        loggerFactory, standbyPollingRateHz = 0.5, actionPollingRateHz = 0.5)
    )
    val hcdProbe = testKit.createTestProbe[HcdState]()

    // First AI timeout: tolerated, no fault.
    statusMonitor ! ControllerStatusActor.PollAnalogInputs
    Thread.sleep(100)
    internalState ! InternalStateActor.GetHcdState(hcdProbe.ref)
    hcdProbe.receiveMessage().state should not be HcdStateEnum.Faulted

    // Second consecutive AI timeout: escalates to Faulted with an accurate reason.
    statusMonitor ! ControllerStatusActor.PollAnalogInputs
    Thread.sleep(100)
    internalState ! InternalStateActor.GetHcdState(hcdProbe.ref)
    val faulted = hcdProbe.receiveMessage()
    faulted.state should be (HcdStateEnum.Faulted)
    faulted.controllerErrorMsg should include ("AI read timeouts")
  }

  test("ControllerStatusActor resets the AI timeout count after a successful read") {
    val internalState = testKit.spawn(InternalStateActor())
    // throw, succeed (resets count), throw → final count is 1, NOT a fault.
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(aiTimeoutSeqIo(Seq(true, false, true)), internalState,
        loggerFactory, standbyPollingRateHz = 0.5, actionPollingRateHz = 0.5)
    )
    val hcdProbe = testKit.createTestProbe[HcdState]()

    statusMonitor ! ControllerStatusActor.PollAnalogInputs  // timeout  (count 1)
    statusMonitor ! ControllerStatusActor.PollAnalogInputs  // success  (count 0)
    statusMonitor ! ControllerStatusActor.PollAnalogInputs  // timeout  (count 1 again)
    Thread.sleep(150)
    internalState ! InternalStateActor.GetHcdState(hcdProbe.ref)
    // Without the reset, the 3rd read would be the 2nd *consecutive* timeout and fault.
    hcdProbe.receiveMessage().state should not be HcdStateEnum.Faulted
  }

  test("ControllerStatusActor tolerates malformed QR records, faults after the threshold") {
    val internalState = testKit.spawn(InternalStateActor())
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(qrFaultSeqIo(Seq("malformed", "malformed", "malformed")),
        internalState, loggerFactory, standbyPollingRateHz = 0.5, actionPollingRateHz = 0.5)
    )
    val hcdProbe = testKit.createTestProbe[HcdState]()

    // Two malformed records: drained/resynced and tolerated (MaxConsecutiveFormatErrors = 3).
    statusMonitor ! ControllerStatusActor.PollController
    statusMonitor ! ControllerStatusActor.PollController
    Thread.sleep(100)
    internalState ! InternalStateActor.GetHcdState(hcdProbe.ref)
    hcdProbe.receiveMessage().state should not be HcdStateEnum.Faulted

    // Third consecutive malformed record: escalates to Faulted with an accurate reason.
    statusMonitor ! ControllerStatusActor.PollController
    Thread.sleep(100)
    internalState ! InternalStateActor.GetHcdState(hcdProbe.ref)
    val faulted = hcdProbe.receiveMessage()
    faulted.state should be (HcdStateEnum.Faulted)
    faulted.controllerErrorMsg should include ("malformed QR data records")
  }

  test("ControllerStatusActor: a malformed QR record clears the timeout count") {
    val internalState = testKit.spawn(InternalStateActor())
    // timeout (count 1), malformed (drains, resets timeout count to 0), timeout (count 1
    // again) → never two *consecutive* timeouts, so this must NOT fault.
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(qrFaultSeqIo(Seq("timeout", "malformed", "timeout")),
        internalState, loggerFactory, standbyPollingRateHz = 0.5, actionPollingRateHz = 0.5)
    )
    val hcdProbe = testKit.createTestProbe[HcdState]()

    statusMonitor ! ControllerStatusActor.PollController  // timeout   (timeout count 1)
    statusMonitor ! ControllerStatusActor.PollController  // malformed (timeout count → 0)
    statusMonitor ! ControllerStatusActor.PollController  // timeout   (timeout count 1)
    Thread.sleep(150)
    internalState ! InternalStateActor.GetHcdState(hcdProbe.ref)
    hcdProbe.receiveMessage().state should not be HcdStateEnum.Faulted
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
    // Switch byte layout (per Galil TS / QR DataRecord):
    //   bit 3 = Forward Limit INACTIVE (clear bit 3 → forward limit HIT after parser inversion)
    //   bit 2 = Reverse Limit INACTIVE (clear bit 2 → reverse limit HIT after parser inversion)
    //   bit 1 = Home switch status
    //   bit 0 = Stepper Mode
    //
    // Axis A: forward limit hit (bit 3 clear), reverse limit clear (bit 2 set), home active (bit 1 set)
    //   → bits 1+2 set, bit 3 clear → 0x06
    val switchesA: Byte = (0x04 | 0x02).toByte
    // Axis B: forward limit clear (bit 3 set), reverse limit hit (bit 2 clear), home inactive
    //   → bit 3 set, bit 2 clear → 0x08
    val switchesB: Byte = 0x08.toByte
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
    stateA.forwardLimit should be(true)   // bit 3 clear → limit hit
    stateA.reverseLimit should be(false)  // bit 2 set → limit clear
    stateA.homeSwitch should be(true)
    stateA.isStepper should be(false)
    stateA.negativeDirection should be(false)
    stateA.motorOff should be(false)
    val probeB = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.B, probeB.ref)
    val stateB = probeB.receiveMessage().get
    stateB.forwardLimit should be(false)  // bit 3 set → limit clear
    stateB.reverseLimit should be(true)   // bit 2 clear → limit hit
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
    // Stub IO with thread 1 reporting as alive via _XQ1.
    val liveThreads = scala.collection.mutable.Set[Int](1)
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithLiveThreads(liveThreads),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    // Wire IS→CS forwarding so RegisterThread propagates to CS's axisThreads.
    internalState ! InternalStateActor.SetStatusActor(statusMonitor)
    Thread.sleep(20)
    // Register axis A on thread 1 (as CommandHandlerActor does after XQ #HomeA,1)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // QR reports thread 1 active (bit 1 = 0x02). Synthesized byte from _XQ
    // will also have bit 1 set since liveThreads contains 1.
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
    val liveThreads = scala.collection.mutable.Set[Int](1, 2)
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithLiveThreads(liveThreads),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    internalState ! InternalStateActor.SetStatusActor(statusMonitor)
    Thread.sleep(20)
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
    val liveThreads = scala.collection.mutable.Set[Int](1)
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithLiveThreads(liveThreads),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    internalState ! InternalStateActor.SetStatusActor(statusMonitor)
    Thread.sleep(20)
    // Register axis A on thread 1, then simulate QR with thread active
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    val dr1 = createExtendedDataRecord(threadStatus = 0x02.toByte)
    statusMonitor ! ControllerStatusActor.QRResponse(dr1)
    Thread.sleep(100)
    val probeA1 = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probeA1.ref)
    probeA1.receiveMessage().get.activeThread should be(1)
    // Now thread 1 stops — registry clears it and sets activeThread=0.
    // Update both the QR byte (controller's view) AND liveThreads (so _XQ
    // returns -1 for thread 1). The synthesized byte will be 0.
    liveThreads -= 1
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
    val liveThreads = scala.collection.mutable.Set[Int](1)
    val statusMonitor = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithLiveThreads(liveThreads),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    internalState ! InternalStateActor.SetStatusActor(statusMonitor)
    Thread.sleep(20)
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

  // ========================================
  // _XQ<n> per-thread status synthesis is authoritative over QR threadStatus (S53)
  //
  // The breakthrough that fixed CMDERR-mid-motion thread-status reporting.
  // `MG _NO` and the QR `threadStatus` byte can remain stale for many seconds
  // post-CMDERR while other threads are running.  `_XQ<n>` is queried per scan
  // and is authoritative.  Regression here silently reintroduces the
  // "thread reports active for many seconds after CMDERR" bug.
  // ========================================

  test("_XQ<n> synthesis overrides stale QR threadStatus byte (post-CMDERR regression case)") {
    // The original S53 bug: CMDERR halts thread 1, but QR threadStatus byte's
    // bit 1 remains set for many seconds because other threads keep _NO
    // sticky.  _XQ1 returns -1 immediately, so the synthesized byte's bit 1
    // is clear and CS reports activeThread=0.
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    // _XQ reports thread 1 has stopped (not in liveThreads), but the QR byte
    // we inject will still claim bit 1 set — the bug scenario.
    val liveThreads = scala.collection.mutable.Set[Int]()
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithLiveThreads(liveThreads),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // Send QR with stale-on threadStatus byte (bit 1 set) — the bug condition.
    val dr = createExtendedDataRecord(threadStatus = 0x02.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(100)
    // Authoritative result: _XQ1=-1 → synthesized byte 0x00 → activeThread=0.
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    probe.receiveMessage().get.activeThread shouldBe 0
  }

  test("_XQ<n> synthesis falls back to QR byte when query result count is wrong (fail-closed)") {
    // If readXqValues returns fewer values than expected (parse failure or
    // simulator without _XQ support), the synthesized byte falls back to the
    // raw QR byte.  This is the fail-closed branch — better to report "still
    // running" than spuriously complete.  Test: stub returns empty result for
    // _XQ (simulating a parse-fail in production), QR byte says thread 1
    // active.  Expect activeThread=1 (fallback honored).
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    // Stub that returns ":" for any MG _XQ — simulating a parse failure.
    val failingXqIo = new csw.proto.galil.io.GalilIo {
      import org.apache.pekko.util.ByteString
      override protected def write(sendBuf: Array[Byte]): Unit = ()
      override protected def read(): ByteString = ByteString(":")
      override def drainAndShowBuffer(timeoutMs: Int = 200): String = ""
      override def close(): Unit = ()
    }
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(failingXqIo,
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // QR byte says thread 1 active — expected to be honored as fallback.
    val dr = createExtendedDataRecord(threadStatus = 0x02.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(100)
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    // Fail-closed: trust the QR byte's "still running" claim.
    probe.receiveMessage().get.activeThread shouldBe 1
  }

  test("_XQ<n> synthesis: registered thread running → bit set in synthesized byte") {
    // Companion to the regression test above: when _XQ does report the thread
    // running, the synthesized byte correctly reflects that.  Combined with
    // the regression test, demonstrates _XQ is the source of truth.
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int](1)
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithLiveThreads(liveThreads),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0)
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // Even if QR byte claims thread NOT active, _XQ is authoritative.
    val dr = createExtendedDataRecord(threadStatus = 0x00.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(100)
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    probe.receiveMessage().get.activeThread shouldBe 1
  }

  // ========================================
  // ae[axis] embedded error code interpretation (S53)
  //
  // ae[] codes set by motion programs:
  //   1 = generic program failure (entry-time flag never cleared — CMDERR
  //       killed the program before it reached the success path).  Attributed
  //       via TC fetch + axis match when an errorCode is also latched.
  //   2 = #POSERR  ("Position error exceeded limit")
  //   3 = #LIMSWI  ("Limit switch hit")
  //   4 = #MCTIME  ("Motion timeout")
  //
  // Codes 2/3/4 are reported independently of errorCode (the embedded error
  // handlers set ae[] but do not generate a controller error latch).  ae[]==1
  // attribution requires errorCode != 0 and the thread to have just cleared.
  // ========================================

  test("ae[axis]==2 (POSERR) reports 'Position error exceeded limit' on the axis") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int]()
    val ae = scala.collection.mutable.Map[Int, Int](Axis.A.index -> 2)
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithThreadsAndAe(liveThreads, ae),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0,
        configuredAxes = Set(Axis.A, Axis.B))
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    // No latched controller error; ae[A]=2 alone should fire the per-axis path.
    val dr = createExtendedDataRecord(threadStatus = 0x00.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(150)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    cmdProbe.receiveMessage().get.axisErrorMsg shouldBe "Position error exceeded limit"
    val stateProbe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, stateProbe.ref)
    stateProbe.receiveMessage().get.axisState shouldBe AxisStateEnum.Error
  }

  test("ae[axis]==3 (LIMSWI) reports 'Limit switch hit'") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int]()
    val ae = scala.collection.mutable.Map[Int, Int](Axis.B.index -> 3)
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithThreadsAndAe(liveThreads, ae),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0,
        configuredAxes = Set(Axis.A, Axis.B))
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    val dr = createExtendedDataRecord(threadStatus = 0x00.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(150)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.B, cmdProbe.ref)
    cmdProbe.receiveMessage().get.axisErrorMsg shouldBe "Limit switch hit"
  }

  test("ae[axis]==4 (MCTIME) reports 'Motion timeout'") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int]()
    val ae = scala.collection.mutable.Map[Int, Int](Axis.A.index -> 4)
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithThreadsAndAe(liveThreads, ae),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0,
        configuredAxes = Set(Axis.A, Axis.B))
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    val dr = createExtendedDataRecord(threadStatus = 0x00.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(150)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    cmdProbe.receiveMessage().get.axisErrorMsg shouldBe "Motion timeout"
  }

  test("ae[axis]==0 (no error) does NOT fire per-axis error path") {
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int]()
    val ae = scala.collection.mutable.Map[Int, Int]() // all zero
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithThreadsAndAe(liveThreads, ae),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0,
        configuredAxes = Set(Axis.A, Axis.B))
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    val dr = createExtendedDataRecord(threadStatus = 0x00.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(150)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    cmdProbe.receiveMessage().get.axisErrorMsg shouldBe ""
  }

  test("ae[axis]==1 without thread clearing is ignored (in-flight program)") {
    // ae[]==1 is the entry-time flag set by every motion program; it stays 1
    // for the duration of the program.  We must NOT treat that as an error
    // while the thread is still running.  Test: ae[A]=1, thread 1 still
    // active for axis A, no errorCode.  Expect NO axis error.
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int](1)
    val ae = scala.collection.mutable.Map[Int, Int](Axis.A.index -> 1)
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithThreadsAndAe(liveThreads, ae),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0,
        configuredAxes = Set(Axis.A, Axis.B))
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // QR byte and _XQ both report thread 1 still running.
    val dr = createExtendedDataRecord(threadStatus = 0x02.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(150)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    // No error: program is in flight, ae==1 is the entry-time flag (normal).
    cmdProbe.receiveMessage().get.axisErrorMsg shouldBe ""
  }

  test("ae[axis]==1 with thread cleared and no errorCode → 'Embedded program ended unexpectedly'") {
    // The Step-3 defensive edge case: thread exited without clearing ae[] and
    // without any controller error.  This shouldn't happen with the
    // documented #X.../#StopX convention, but is treated as a per-axis Error
    // defensively (per the decideAxisAndControllerErrors code).
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int]() // thread 1 stopped
    val ae = scala.collection.mutable.Map[Int, Int](Axis.A.index -> 1)
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithThreadsAndAe(liveThreads, ae),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0,
        configuredAxes = Set(Axis.A, Axis.B))
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // Thread cleared, no errorCode, ae[A]=1 — the defensive case.
    val dr = createExtendedDataRecord(threadStatus = 0x00.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(150)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    cmdProbe.receiveMessage().get.axisErrorMsg shouldBe
      "Embedded program ended unexpectedly without controller error"
    val stateProbe = testKit.createTestProbe[Option[AxisState]]()
    internalState ! InternalStateActor.GetAxisState(Axis.A, stateProbe.ref)
    stateProbe.receiveMessage().get.axisState shouldBe AxisStateEnum.Error
  }

  test("S55: NotifyAxisHalted pruning prevents spurious ae==1 error after CH-initiated HX") {
    // The S55 edge case: CommandHandlerActor's checkAndInterrupt issues HX to
    // stop a running axis program before launching a new one.  The next QR
    // scan sees ae[axis]==1 (the entry-time flag from the halted program that
    // never reached its success path).  Without pruning, CS would treat this
    // as the defensive "program ended unexpectedly" Error.
    //
    // Fix: CH sends NotifyAxisHalted(axis) right after the HX, which prunes
    // the axis from CS's axisThreads map.  Without the axis in axisThreads,
    // axesWithClearedThread won't include it, and the defensive Step-3 check
    // won't fire.
    //
    // Test: register axis A on thread 1, simulate halt via NotifyAxisHalted,
    // then drive a scan where thread is cleared AND ae[A]==1.  Expect NO
    // axis error.
    val internalState = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    ))
    val liveThreads = scala.collection.mutable.Set[Int]()
    val ae = scala.collection.mutable.Map[Int, Int](Axis.A.index -> 1)
    val sm = testKit.spawn(
      ControllerStatusActor.withIo(stubIoWithThreadsAndAe(liveThreads, ae),
        internalState, loggerFactory, standbyPollingRateHz = 10.0, actionPollingRateHz = 10.0,
        configuredAxes = Set(Axis.A, Axis.B))
    )
    internalState ! InternalStateActor.SetStatusActor(sm)
    Thread.sleep(20)
    internalState ! InternalStateActor.RegisterThread(1, Axis.A)
    Thread.sleep(20)
    // Simulate the post-HX prune — CH would send this synchronously after HX.
    val ackProbe = testKit.createTestProbe[ControllerStatusActor.NotifyAxisHaltedAck]()
    sm ! ControllerStatusActor.NotifyAxisHalted(Axis.A, ackProbe.ref)
    ackProbe.receiveMessage(500.millis)
    // Drive a scan: thread cleared, ae[A]==1 (residual entry-time flag).
    val dr = createExtendedDataRecord(threadStatus = 0x00.toByte)
    sm ! ControllerStatusActor.QRResponse(dr)
    Thread.sleep(150)
    val cmdProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    internalState ! InternalStateActor.GetAxisCmdState(Axis.A, cmdProbe.ref)
    // No error — the prune kept axis A out of axesWithClearedThread.
    cmdProbe.receiveMessage().get.axisErrorMsg shouldBe ""
  }