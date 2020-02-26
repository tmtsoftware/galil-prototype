package csw.proto.galil.hcd

import com.typesafe.config.Config
import csw.params.commands.CommandResponse.{Completed, SubmitResponse}
import csw.params.commands.{Result, Setup}
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models.{Id, ObsId}
import csw.proto.galil.hcd.CSWDeviceAdapter.{CommandMapEntry, ParamDefEntry, commandParamKeyMap, paramRegex}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

//noinspection DuplicatedCode
object CSWDeviceAdapter {
  case class CommandMapEntry(name: String, command: String, responseFormat: String)
  private type CommandMap = Map[String, CommandMapEntry]
  private case class ParamDefEntry(name: String, typeStr: String, range: String, dataRegex: String)
  private type ParamDefMap = Map[String, ParamDefEntry]

  //  // --- command parameter keys ---

  val axisKey: Key[Char] = KeyType.CharKey.make("axis")
  val eDescKey: Key[String] = KeyType.StringKey.make("eDesc")
  val mTypeKey: Key[Double] = KeyType.DoubleKey.make("mType")
  val eCodeKey: Key[Int] = KeyType.IntKey.make("eCode")
  val swStatusKey: Key[Int] = KeyType.IntKey.make("swStatus")
  val lcParamKey: Key[Int] = KeyType.IntKey.make("lcParam")
  val smoothKey: Key[Double] = KeyType.DoubleKey.make("smooth")
  val speedKey: Key[Int] = KeyType.IntKey.make("speed")
  val countsKey: Key[Int] = KeyType.IntKey.make("counts")
  val interpCountsKey: Key[Int] = KeyType.IntKey.make("interpCounts")
  val brushlessModulusKey: Key[Int] = KeyType.IntKey.make("brushlessModulus")
  val voltsKey: Key[Double] = KeyType.DoubleKey.make("volts")


  // Map key name to key
  private val commandParamKeys: List[Key[_]] = List(axisKey, eDescKey, mTypeKey, eCodeKey,
    swStatusKey, lcParamKey, smoothKey, speedKey, countsKey, interpCountsKey, brushlessModulusKey, voltsKey)

  private val commandParamKeyMap: Map[String, Key[_]] = commandParamKeys.map(k => k.keyName -> k).toMap


  // Used to extract parameter names from command
  private val paramRegex = raw"\(([A-Za-z]*)\)".r
}
//noinspection DuplicatedCode
class CSWDeviceAdapter(config: Config) {
  private val cmdConfig = config.getConfig("commandMap")
  private val cmdNames = cmdConfig.root.keySet().asScala.toList
  private val cmdMap = cmdNames.map { cmdName =>
    val config = cmdConfig.getConfig(cmdName)
    cmdName -> CommandMapEntry(
      cmdName,
      config.getString("command"),
      config.getString("responseFormat"))
  }.toMap

  private val paramDefConfig = config.getConfig("paramDefMap")
  private val paramDefNames = paramDefConfig.root.keySet().asScala.toList
  private val paramDefMap = paramDefNames.map { paramDefName =>
    val config = paramDefConfig.getConfig(paramDefName)
    paramDefName -> ParamDefEntry(
      paramDefName,
      config.getString("type"),
      if (config.hasPath("range")) config.getString("range") else "",
      config.getString("dataRegex"))
  }.toMap

  def getCommandMapEntry(setup: Setup): Try[CommandMapEntry] = {
    Try(cmdMap(setup.commandName.name)) // TODO: FIXME: return Option
  }

  def validateSetup(setup: Setup, cmdEntry: CommandMapEntry): Try[String] = {
    // Look up the paramDef entries defined in the command string
    val paramDefs = paramRegex.
      findAllMatchIn(cmdEntry.command)
      .toList
      .map(_.group(1))
      .map(paramDefMap(_))

    // Check missing params
    val missing = paramDefs.flatMap { p =>
      val key = commandParamKeyMap(p.name)
      if (setup.contains(key)) None else Some(new RuntimeException(s"Missing ${key.keyName} parameter"))
    }

    if (missing.nonEmpty)
      Failure(missing.head)
    else
      Success(insertParams(setup, cmdEntry.command, paramDefs))
  }

  // Replaces the placeholders for the parameters with the parameter values
  @tailrec
  private def insertParams(setup: Setup, cmd: String, paramDefs: List[ParamDefEntry]): String = {
    paramDefs match {
      case h :: t =>
        val key = commandParamKeyMap(h.name)
        val param = setup.get(key).get
        val s = cmd.replace(s"(${key.keyName})", param.head.toString)
        insertParams(setup, s, t)
      case Nil => cmd
    }
  }

  // Parses and returns the command's response
  def makeResponse(runId: Id, maybeObsId: Option[ObsId], cmdEntry: CommandMapEntry, responseStr: String): SubmitResponse = {
    if (cmdEntry.responseFormat.isEmpty) {
      Completed(runId)
    } else {
      // Look up the paramDef entries defined in the response string
      val paramDefs = paramRegex.
        findAllMatchIn(cmdEntry.responseFormat)
        .toList
        .map(_.group(1))
        .map(paramDefMap(_))

      val responseFormat = insertResponseRegex(cmdEntry.responseFormat, paramDefs)
      val paramValues = responseFormat.r.findAllIn(responseStr).toList
      val resultParamSet = makeResultParamSet(paramValues, paramDefs, Nil).toSet
      Completed(runId, Result(resultParamSet))
    }
  }

  // Replace command response placeholders with their configured regex values
  @tailrec
  private def insertResponseRegex(responseFormat: String, paramDefs: List[ParamDefEntry]): String = {
    paramDefs match {
      case h :: t =>
        val s = responseFormat.replace(s"(${h.name})", h.dataRegex)
        insertResponseRegex(s, t)
      case Nil => responseFormat
    }
  }

  // Returns a paramSet based on the given response, using the configured regex to extract the parameters
  def makeResultParamSet(paramValues: List[String], paramDefs: List[ParamDefEntry],
                         paramSet: List[Parameter[_]]): List[Parameter[_]] = {

    paramValues.zip(paramDefs).map { pair =>
      val name = pair._2.name.trim
      val key = commandParamKeyMap(name)
      val typeStr = pair._2.typeStr.trim
      val valueStr = pair._1.trim

      typeStr match {
        case "char" => key.asInstanceOf[Key[Char]].set(valueStr.charAt(0))
        case "string" => key.asInstanceOf[Key[String]].set(valueStr)
        case "double" => key.asInstanceOf[Key[Double]].set(valueStr.toDouble)
        case "int" => key.asInstanceOf[Key[Int]].set(valueStr.toInt)
      }
    }
  }
}
