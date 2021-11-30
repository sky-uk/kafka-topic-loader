organization := "com.sky"
scalaVersion := "2.13.7"
name := "kafka-topic-loader"

crossScalaVersions := Seq("2.12.15", "2.13.7")

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

publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"

scalafmtVersion := "1.5.1"
scalafmtOnCompile := true

Test / parallelExecution := false
Test / fork := true

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

//resolvers ++= Seq("segence" at "https://dl.bintray.com/segence/maven-oss-releases/")

addCommandAlias("checkFmt", ";scalafmt::test; test:scalafmt::test; sbt:scalafmt::test")
addCommandAlias("runFmt", ";scalafmt; test:scalafmt; sbt:scalafmt")
addCommandAlias("ciBuild", ";checkFmt; clean; +test")
