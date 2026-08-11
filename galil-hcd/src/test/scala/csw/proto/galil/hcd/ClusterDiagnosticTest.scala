package csw.proto.galil.hcd

import com.typesafe.config.ConfigFactory
import csw.testkit.scaladsl.CSWService.{AlarmServer, EventServer}
import csw.testkit.scaladsl.ScalaTestFrameworkTestKit
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.Ignore

/**
 * Minimal diagnostic test to debug FrameworkTestKit cluster formation.
 * Run with: sbt "galil-hcd/testOnly *ClusterDiagnosticTest"
 */

@Ignore
class ClusterDiagnosticTest
  extends ScalaTestFrameworkTestKit(AlarmServer, EventServer)
  with AnyFunSuiteLike:

  override def beforeAll(): Unit =
    // Print diagnostic info before attempting cluster formation
    println("=== CLUSTER DIAGNOSTIC ===")
    println(s"INTERFACE_NAME env: ${sys.env.getOrElse("INTERFACE_NAME", "NOT SET")}")
    println(s"INTERFACE_NAME prop: ${sys.props.getOrElse("INTERFACE_NAME", "NOT SET")}")
    println(s"TMT_LOG_HOME env: ${sys.env.getOrElse("TMT_LOG_HOME", "NOT SET")}")
    println(s"clusterSeeds env: ${sys.env.getOrElse("clusterSeeds", "NOT SET")}")
    println(s"CLUSTER_SEEDS env: ${sys.env.getOrElse("CLUSTER_SEEDS", "NOT SET")}")

    // Show effective Pekko config
    val config = ConfigFactory.load()
    println(s"=== Effective Pekko Config ===")
    try println(s"pekko.actor.provider = ${config.getString("pekko.actor.provider")}") catch case _: Exception => println("pekko.actor.provider: NOT SET")
    try println(s"pekko.remote.artery.enabled = ${config.getBoolean("pekko.remote.artery.enabled")}") catch case _: Exception => println("pekko.remote.artery.enabled: NOT SET")
    try println(s"pekko.remote.artery.transport = ${config.getString("pekko.remote.artery.transport")}") catch case _: Exception => println("pekko.remote.artery.transport: NOT SET")
    try println(s"pekko.remote.artery.canonical.hostname = ${config.getString("pekko.remote.artery.canonical.hostname")}") catch case _: Exception => println("pekko.remote.artery.canonical.hostname: NOT SET")
    try println(s"pekko.remote.artery.canonical.port = ${config.getInt("pekko.remote.artery.canonical.port")}") catch case _: Exception => println("pekko.remote.artery.canonical.port: NOT SET")
    try println(s"pekko.cluster.seed-nodes = ${config.getStringList("pekko.cluster.seed-nodes")}") catch case _: Exception => println("pekko.cluster.seed-nodes: NOT SET or empty")
    try println(s"startManagement = ${config.getBoolean("startManagement")}") catch case _: Exception => println("startManagement: NOT SET")

    // Check the critical csw-location-server block
    println(s"=== CSW Location Server Config Block ===")
    try
      val lsConfig = config.getConfig("csw-location-server")
      println(s"csw-location-server block EXISTS")
      try println(s"  csw-location-server.pekko.actor.provider = ${lsConfig.getString("pekko.actor.provider")}") catch case _: Exception => println("  csw-location-server.pekko.actor.provider: NOT SET")
      try println(s"  csw-location-server.cluster-port = ${lsConfig.getInt("cluster-port")}") catch case _: Exception => println("  csw-location-server.cluster-port: NOT SET")
      try println(s"  csw-location-server.http-port = ${lsConfig.getInt("http-port")}") catch case _: Exception => println("  csw-location-server.http-port: NOT SET")
    catch case _: Exception => println("csw-location-server block MISSING - this is the problem!")

    // Show java.net info
    try
      val iface = java.net.NetworkInterface.getByName("en0")
      if iface != null then
        println(s"=== en0 Interface ===")
        println(s"  isUp: ${iface.isUp}")
        println(s"  isLoopback: ${iface.isLoopback}")
        val addrs = iface.getInetAddresses
        while addrs.hasMoreElements do
          val addr = addrs.nextElement()
          println(s"  address: ${addr.getHostAddress} (${addr.getClass.getSimpleName})")
      else
        println("en0 interface: NOT FOUND")
    catch case e: Exception => println(s"Network interface error: ${e.getMessage}")

    println("=== Attempting super.beforeAll() (cluster formation) ===")
    try
      super.beforeAll()
      println("=== Cluster formation SUCCEEDED ===")
    catch
      case e: Exception =>
        println(s"=== Cluster formation FAILED: ${e.getMessage} ===")
        throw e

  override def afterAll(): Unit =
    try super.afterAll() catch case _: Exception => ()

  test("diagnostic - cluster should form") {
    println("If you see this, cluster formed successfully!")
    assert(true)
  }