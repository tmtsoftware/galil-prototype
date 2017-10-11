package csw.proto.galil.commands

import com.typesafe.config.{Config, ConfigFactory}

object GalilCommands {
  def apply(): GalilCommands = {
    val config = ConfigFactory.load("GalilCommands.conf")
    // TODO: parse config and pass maps to constructor...
    GalilCommands(config)
  }
}

case class GalilCommands(config: Config) {

}
