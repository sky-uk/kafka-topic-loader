import com.lucidchart.sbt.scalafmt.ScalafmtCorePlugin.autoImport._
import Aliases._
import Bintray._
import BuildInfo._
import Release._

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, UniversalDeployPlugin)
  .settings(
    defineCommandAliases,
    organization := "com.sky",
    scalaVersion := "2.12.6",
    version := "1.0.0",
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
    scalafmtVersion := "1.2.0",
    scalafmtOnCompile := sys.env.getOrElse("RUN_SCALAFMT_ON_COMPILE", "false").toBoolean,
    libraryDependencies ++= Dependencies.deps,
    buildInfoSettings,
    releaseSettings,
    bintraySettings
  )
