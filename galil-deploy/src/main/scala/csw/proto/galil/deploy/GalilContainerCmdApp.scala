package csw.proto.galil.deploy

import csw.framework.deploy.containercmd.ContainerCmd


object GalilContainerCmdApp extends App {
  ContainerCmd.start("GalilContainerCmd", args)
}
