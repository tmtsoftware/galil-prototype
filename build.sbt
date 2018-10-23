import sbt.Keys._
import sbt._
import Dependencies._
import Settings._

// The Galil prototype HCD, implemented in Scala
lazy val `galil-hcd` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= `galil-hcd-deps`)
  .dependsOn(`galil-io`, `galil-commands`)

// The Galil prototype assembly, implemented in Scala
lazy val `galil-assembly` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= `galil-assembly-deps`)

// A Scala client application that talks to the Galil assembly
lazy val `galil-client` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= `galil-client-deps`)
  .dependsOn(`galil-io`)

// A Galil hardware simulator
lazy val `galil-simulator` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= `galil-simulator-deps`)
  .dependsOn(`galil-io`)

// A REPL client to test talking to the Galil hardware or simulator
lazy val `galil-repl` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= `galil-repl-deps`)
  .dependsOn(`galil-io`)

// Supports talking to and simulating a Galil device
lazy val `galil-io` = project
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= `galil-io-deps`)

// Supports Galil commands and responses as described in a config file
lazy val `galil-commands` = project
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= `galil-commands-deps`)
  .dependsOn(`galil-io`)

// Container deployment
lazy val `galil-deploy` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= `galil-deploy-deps`)
  .dependsOn(`galil-assembly`, `galil-hcd`)
