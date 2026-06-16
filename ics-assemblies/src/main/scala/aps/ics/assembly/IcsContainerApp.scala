package aps.ics.assembly

import com.typesafe.config.ConfigFactory
import csw.framework.deploy.containercmd.ContainerCmd
import csw.prefix.models.Subsystem

/**
 * Generic launcher for ICS assembly containers.
 *
 * With no `--local`/`--standalone` argument it starts the combined
 * IcsAssembliesContainer (every ICS assembly in one JVM), so all assemblies
 * start and stop together. Pass `--local <file>` to start a different container
 * config (e.g. a single-assembly container).
 *
 * The per-assembly apps (InsertionStageApp, SteeringBeamSplitterStageApp) remain
 * for bringing up one assembly on its own.
 */
object IcsContainerApp:
  def main(args: Array[String]): Unit =
    val default = ConfigFactory.load("IcsAssembliesContainer.conf")
    ContainerCmd.start("IcsContainer", Subsystem.APS, args, Some(default))
