import BuildInfo._

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    organization := "com.sky",
    scalaVersion := "2.12.6",
    version := "0.1.0-SNAPSHOT",
    name := "kafka-topic-loader",
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
    libraryDependencies ++= Dependencies.deps
  )
