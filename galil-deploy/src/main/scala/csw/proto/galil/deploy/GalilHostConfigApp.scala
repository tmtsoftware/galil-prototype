package csw.proto.galil.deploy

import csw.framework.deploy.hostconfig.HostConfig

object GalilHostConfigApp extends App {
  HostConfig.start("Galil-Prototypes", args)
}
