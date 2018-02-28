package csw.proto.galil.deploy

import csw.apps.hostconfig.HostConfig

object GalilHostConfigApp extends App {
  HostConfig.start("Galil-Prototypes", args)
}
