organization := "com.sky"
scalaVersion := "2.13.6"
name := "kafka-topic-loader"

crossScalaVersions := Seq("2.12.14", "2.13.6")

// format: off
ThisBuild / scalacOptions ++= Seq(
  "-encoding", "utf8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Vimplicits",
  "-Vtype-diffs",
  "-Xlint:unused",
  "-Ymacro-annotations",
  "-Xsource:3",
  "-Werror",
  "-Wdead-code",
  "-Wunused:patvars",
  "-Wunused:params"
)
// format: on

scalafmtVersion := "1.5.1"
scalafmtOnCompile := true

Test / parallelExecution := false
Test / fork := true

releaseCrossBuild := true
licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

val AkkaVersion    = "2.6.16"
val CatsVersion    = "2.6.1"
val KafkaVersion   = "2.8.0"
val RefinedVersion = "0.9.27"

// @formatter:off
libraryDependencies ++= Seq(
  "com.typesafe.akka"           %% "akka-stream"              % AkkaVersion,
  "com.typesafe.akka"           %% "akka-stream-kafka"        % "2.1.1",
  "org.apache.kafka"             % "kafka-clients"            % KafkaVersion,
  "com.typesafe.scala-logging"  %% "scala-logging"            % "3.9.4",
  "org.typelevel"               %% "cats-core"                % CatsVersion,
  "org.typelevel"               %% "cats-kernel"              % CatsVersion,
  "eu.timepit"                  %% "refined"                  % RefinedVersion,
  "eu.timepit"                  %% "refined-pureconfig"       % RefinedVersion,
  "com.github.pureconfig"       %% "pureconfig"               % "0.17.0",
  "org.scala-lang.modules"      %% "scala-collection-compat"  % "2.5.0",
  "com.typesafe.akka"           %% "akka-stream-testkit"      % AkkaVersion   % Test,
  "com.typesafe.akka"           %% "akka-testkit"             % AkkaVersion   % Test,
  "org.scalatest"               %% "scalatest"                % "3.2.9"       % Test,
  "io.github.embeddedkafka"     %% "embedded-kafka"           % KafkaVersion  % Test
)
// @formatter:on

resolvers ++= Seq("segence" at "https://dl.bintray.com/segence/maven-oss-releases/")

addCommandAlias("checkFmt", ";scalafmt::test; test:scalafmt::test; sbt:scalafmt::test")
addCommandAlias("runFmt", ";scalafmt; test:scalafmt; sbt:scalafmt")
addCommandAlias("ciBuild", ";checkFmt; clean; +test")
