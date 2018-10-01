import BuildInfo._

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    organization := "com.sky",
    scalaVersion := "2.12.6",
    version := "0.1.0-SNAPSHOT",
    name := "kafka-topic-loader",
    libraryDependencies ++= Dependencies.deps
  )
