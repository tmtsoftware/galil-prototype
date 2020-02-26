package csw.proto.galil.deploy

import csw.framework.deploy.containercmd.ContainerCmd
import csw.prefix.models.Subsystem.CSW


object GalilContainerCmdApp extends App {
  ContainerCmd.start("galil.container.GalilContainerCmd", CSW, args)
}
