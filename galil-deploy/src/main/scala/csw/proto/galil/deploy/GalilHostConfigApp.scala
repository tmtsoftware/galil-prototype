package csw.proto.galil.deploy

import csw.framework.deploy.hostconfig.HostConfig
import csw.prefix.models.Subsystem.CSW

object GalilHostConfigApp extends App {
  HostConfig.start("galil_host_config_app", CSW, args)
}
