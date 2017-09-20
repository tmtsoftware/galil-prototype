import sbt.Keys._
import sbt._

import Dependencies._
import Settings._

// The Galil prototype HCD, implemented in Scala
lazy val `scala-hcd` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// The Galil prototype assembly, implemented in Scala
lazy val `scala-assembly` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// The Galil prototype HCD, implemented in Java
lazy val `java-hcd` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// The Galil prototype assembly, implemented in Java
lazy val `java-assembly` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// A Scala client application that talks to the Galil assembly
lazy val `scala-client` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// A Java client application that talks to the Galil assembly
lazy val `java-client` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// A Galil hardware simulator
lazy val `simulator` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// A REPL client to test talking to the Galil hardware or simulator
lazy val `simulatorRepl` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`
  ))

// Supports talking to and simulating a Galil device
lazy val `galil-io` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-framework`,
    scalaTest % Test
  )).dependsOn(simulator)

