package csw.proto.galil.deploy

import csw.apps.containercmd.ContainerCmd

object GalilContainerCmdApp extends App {
  ContainerCmd.start("GalilContainerCmd", args)
}
