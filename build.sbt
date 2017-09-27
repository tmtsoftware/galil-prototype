import sbt.Keys._
import sbt._

import Dependencies._
import Settings._

// The Galil prototype HCD, implemented in Scala
lazy val `galil-hcd` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// The Galil prototype assembly, implemented in Scala
lazy val `galil-assembly` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// A Scala client application that talks to the Galil assembly
lazy val `galil-client` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// A Galil hardware simulator
lazy val `galil-simulator` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// A REPL client to test talking to the Galil hardware or simulator
lazy val `galil-repl` = project
  .enablePlugins(DeployApp)
  .settings(appSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  )).dependsOn(`galil-io`)

// Supports talking to and simulating a Galil device
lazy val `galil-io` = project
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`,
    scalaTest % Test
  ))

