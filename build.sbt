import Dependencies.all

lazy val scala213               = "2.13.7"
lazy val scala212               = "2.12.15"
lazy val supportedScalaVersions = List(scala213, scala212)

organization := "com.sky"
name         := "kafka-topic-loader"

scalaVersion       := scala213
crossScalaVersions := supportedScalaVersions

// format: off
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "utf8",
  "-explaintypes",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-unchecked",
  "-Xcheckinit",
  "-Xfatal-warnings",
  "-Ywarn-dead-code",
  "-Ywarn-extra-implicit",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:imports",
  "-Ywarn-unused:locals",
  "-Ywarn-unused:params",
  "-Ywarn-unused:patvars",
  "-Ywarn-unused:privates",
  "-Ywarn-value-discard"
) ++ {
  if (scalaBinaryVersion.value == "2.13") Seq("-Wconf:msg=annotation:silent")
  else Seq("-Xfuture", "-Ypartial-unification", "-Yno-adapted-args")
}
// format: on

Test / parallelExecution := false
Test / fork              := true
releaseCrossBuild        := true

licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

libraryDependencies ++= all

resolvers ++= Seq("segence" at "https://dl.bintray.com/segence/maven-oss-releases/")

addCommandAlias("checkFmt", ";scalafmt::test; test:scalafmt::test; sbt:scalafmt::test")
addCommandAlias("runFmt", ";scalafmt; test:scalafmt; sbt:scalafmt")
addCommandAlias("ciBuild", ";checkFmt; clean; +test")
