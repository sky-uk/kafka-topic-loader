import com.lucidchart.sbt.scalafmt.ScalafmtCorePlugin.autoImport._
import Aliases._
import Bintray._
import BuildInfo._
import Release._

lazy val root = Project(id = "kafka-topic-loader", base = file("."))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, UniversalDeployPlugin)
  .settings(
    defineCommandAliases,
    organization := "com.sky",
    scalaVersion := "2.12.6",
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
    scalafmtOnCompile := true,
    libraryDependencies ++= Dependencies.deps,
    buildInfoSettings,
    releaseSettings,
    bintraySettings,
    parallelExecution in Test := false,
    fork in Test := true,
  )
