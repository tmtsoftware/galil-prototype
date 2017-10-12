package csw.proto.galil.commands

import com.typesafe.config.ConfigFactory
import csw.proto.galil.io.GalilIo

object GalilCommands {

  /**
    * Returns a DeviceCommands object for talking to a Galil controller
    * @param galilIo used to talk to the Galil controller
    */
  def apply(galilIo: GalilIo): DeviceCommands = {
    val config = ConfigFactory.load("GalilCommands.conf")

    // function to talk to Galil: only send single command and receive single reply
    def galilSend(cmd: String): String = {
      val responses = galilIo.send(cmd)
      if (responses.size != 1)
        throw new RuntimeException(s"Received ${responses.size} responses to Galil $cmd")
      responses.head._2.utf8String
    }

    DeviceCommands(config, galilSend)
  }
}
