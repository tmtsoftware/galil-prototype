//import sbt.Keys._
//import sbt._
//import com.typesafe.sbt.packager.Keys._
//
////noinspection TypeAnnotation
//// Defines the global build settings so they don't need to be edited everywhere
//object Settings {
//  val Version = "0.1-SNAPSHOT"
//  val ScalaVersion = "2.13.0"
//
//  val buildSettings = Seq(
//    organization := "com.github.tmtsoftware.galil-prototype",
//    organizationName := "TMT",
//    organizationHomepage := Some(url("http://www.tmt.org")),
//    version := Version,
//    scalaVersion := ScalaVersion,
////    crossPaths := true,
//    parallelExecution in Test := false,
//    fork := true,
//    autoCompilerPlugins := true,
//    resolvers += "jitpack" at "https://jitpack.io",
//    resolvers += "bintray" at "https://jcenter.bintray.com",
//    updateOptions := updateOptions.value.withLatestSnapshots(false)
//  )
//
//  lazy val defaultSettings = buildSettings ++ Seq(
//    // compile options ScalaUnidoc, unidoc
//    scalacOptions ++= Seq("-encoding", "UTF-8", "-feature", "-deprecation", "-unchecked")
//  )
//
//  lazy val appSettings = defaultSettings ++ Seq(
//    bashScriptExtraDefines ++= Seq(s"addJava -DVERSION=$Version")
//  )
//}
