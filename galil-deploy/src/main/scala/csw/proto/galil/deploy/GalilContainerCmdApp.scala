package csw.proto.galil.deploy

import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd

object GalilContainerCmdApp extends App {
  ContainerCmd.start("", args)
}
