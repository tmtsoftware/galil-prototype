import Dependencies._

lazy val aggregatedProjects: Seq[ProjectReference] = Seq(
  `galil-assembly`,
  `galil-hcd`,
  `galil-client`,
  `galil-simulator`,
  `galil-repl`,
  `galil-io`,
//  `galil-commands`,
  `galil-deploy`,
)

lazy val `galil-root` = project
  .in(file("."))
  .aggregate(aggregatedProjects: _*)

// The Galil prototype HCD
lazy val `galil-hcd` = project
  .enablePlugins(DeployApp)
  .settings(
    libraryDependencies ++= GalilHcd
  )
  .dependsOn(`galil-io`)

// The Galil prototype assembly
lazy val `galil-assembly` = project
  .enablePlugins(DeployApp)
  .settings(
    libraryDependencies ++= GalilAssembly
  )

// A Scala client application that talks to the Galil assembly
lazy val `galil-client` = project
  .enablePlugins(DeployApp)
  .settings(libraryDependencies ++= `GalilClient`)
  .dependsOn(`galil-io`)

// A Galil hardware simulator
lazy val `galil-simulator` = project
  .enablePlugins(DeployApp)
  .settings(libraryDependencies ++= `GalilSimulator`)
  .dependsOn(`galil-io`)

// A REPL client to test talking to the Galil hardware or simulator
lazy val `galil-repl` = project
  .enablePlugins(DeployApp)
  .settings(libraryDependencies ++= `GalilRepl`)
  .dependsOn(`galil-io`)

// Supports talking to and simulating a Galil device
lazy val `galil-io` = project
  .settings(libraryDependencies ++= `GalilIo`)

//// Supports Galil commands and responses as described in a config file
//lazy val `galil-commands` = project
//  .settings(libraryDependencies ++= `GalilCommands`)
//  .dependsOn(`galil-io`)

// deploy module
lazy val `galil-deploy` = project
  .enablePlugins(DeployApp)
  .dependsOn(`galil-assembly`, `galil-hcd`)
  .settings(
    libraryDependencies ++= GalilDeploy
  )

