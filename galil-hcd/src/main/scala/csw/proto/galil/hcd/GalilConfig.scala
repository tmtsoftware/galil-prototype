package csw.proto.galil.hcd

/**
 * Galil configuration
 * @param host host or IP address of the Galil device
 * @param port port number on host
 */
case class GalilConfig(host: String, port: Int)
