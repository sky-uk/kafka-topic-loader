val Scala212 = "2.12.10"
val Scala213 = "2.13.2"

organization := "com.sky"
scalaVersion := "2.12.11"
name := "kafka-topic-loader"

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "utf-8",
  "-explaintypes",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Xcheckinit",
  "-Xfatal-warnings",
  "-Xfuture",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ypartial-unification",
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
)

scalafmtVersion := "1.5.1"
scalafmtOnCompile := true

parallelExecution in Test := false
fork in Test := true

bintrayOrganization := Some("sky-uk")
bintrayReleaseOnPublish in ThisBuild := false
bintrayRepository := "oss-maven"
bintrayVcsUrl := Some("https://github.com/sky-uk/kafka-topic-loader")
licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

val AkkaVersion    = "2.6.5"
val CatsVersion    = "2.1.1"
val RefinedVersion = "0.9.14"

// @formatter:off
libraryDependencies ++= Seq(
  "com.typesafe.akka"           %% "akka-stream"              % AkkaVersion,
  "com.typesafe.akka"           %% "akka-stream-kafka"        % "2.0.3",
  "org.apache.kafka"             % "kafka-clients"            % "2.5.0",
  "com.typesafe.scala-logging"  %% "scala-logging"            % "3.9.2",
  "org.typelevel"               %% "cats-core"                % CatsVersion,
  "org.typelevel"               %% "cats-kernel"              % CatsVersion,
  "eu.timepit"                  %% "refined"                  % RefinedVersion,
  "eu.timepit"                  %% "refined-pureconfig"       % RefinedVersion,
  "com.github.pureconfig"       %% "pureconfig"               % "0.12.3",
  "com.typesafe.akka"           %% "akka-stream-testkit"      % AkkaVersion   % Test,
  "com.typesafe.akka"           %% "akka-testkit"             % AkkaVersion   % Test,
  "org.scalatest"               %% "scalatest"                % "3.1.2"       % Test,
  "io.github.embeddedkafka"     %% "embedded-kafka"           % "2.5.0"       % Test
)
// @formatter:on

resolvers ++= Seq("segence" at "https://dl.bintray.com/segence/maven-oss-releases/")

addCommandAlias("checkFmt", ";scalafmt::test; test:scalafmt::test; sbt:scalafmt::test")
addCommandAlias("runFmt", ";scalafmt; test:scalafmt; sbt:scalafmt")
addCommandAlias("ciBuild", ";checkFmt; clean; test")
