package csw.proto.galil.deploy

import csw.framework.deploy.containercmd.ContainerCmd
import csw.prefix.models.Subsystem.CSW

object GalilContainerCmdApp extends App {
  ContainerCmd.start("galil_container_cmd_app", CSW, args)
}
