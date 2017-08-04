import sbt.Keys._
import sbt._

import Dependencies._
import Settings._

lazy val `scala-hcd` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-params`
  ))

lazy val `scala-assembly` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-params`
  ))

lazy val `java-hcd` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-params`
  ))

lazy val `java-assembly` = project
  .enablePlugins(DeployApp)
  .settings(defaultSettings: _*)
  .settings(libraryDependencies ++= Seq(
    `csw-params`
  ))

