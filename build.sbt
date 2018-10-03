import Bintray._
import BuildInfo._
import Release._

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, UniversalDeployPlugin)
  .settings(
    organization := "com.sky",
    scalaVersion := "2.12.6",
    version := "0.1.0-SNAPSHOT",
    name := "kafka-topic-loader",
    resolvers += Resolver.bintrayRepo("cakesolutions", "maven"),
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-deprecation",
      "-unchecked",
      "-Ywarn-dead-code",
      "-Ywarn-unused",
      "-Xfatal-warnings",
      "-language:implicitConversions",
      "-language:postfixOps",
      "-target:jvm-1.8",
      "-feature",
      "-Ypartial-unification"
    ),
    libraryDependencies ++= Dependencies.deps,
    buildInfoSettings,
    releaseSettings,
    bintraySettings
  )
