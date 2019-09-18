organization := "com.sky"
scalaVersion := "2.12.8"
name := "kafka-topic-loader"

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

val AkkaVersion    = "2.5.23"
val CatsVersion    = "1.6.0"
val RefinedVersion = "0.9.5"

// @formatter:off
libraryDependencies ++= Seq(
  "com.typesafe.akka"           %% "akka-stream"              % AkkaVersion,
  "com.typesafe.akka"           %% "akka-stream-kafka"        % "1.0.3",
  "org.apache.kafka"             % "kafka-clients"            % "2.2.0",
  "com.typesafe.scala-logging"  %% "scala-logging"            % "3.9.2",
  "org.typelevel"               %% "cats-core"                % CatsVersion,
  "org.typelevel"               %% "cats-kernel"              % CatsVersion,
  "eu.timepit"                  %% "refined"                  % RefinedVersion,
  "eu.timepit"                  %% "refined-pureconfig"       % RefinedVersion,
  "com.sky"                     %% "akka-streams"             % "2.0.2",
  "com.github.pureconfig"       %% "pureconfig"               % "0.11.0",
  "com.typesafe.akka"           %% "akka-stream-testkit"      % AkkaVersion   % Test,
  "com.typesafe.akka"           %% "akka-testkit"             % AkkaVersion   % Test,
  "org.scalatest"               %% "scalatest"                % "3.0.5"       % Test,
  "io.github.embeddedkafka"     %% "embedded-kafka"           % "2.2.0"       % Test
)
// @formatter:on

resolvers ++= Seq("segence" at "https://dl.bintray.com/segence/maven-oss-releases/",
                  "discovery-repo" at "http://artifacts.ath.bskyb.com/repository/discovery-repo")

addCommandAlias("checkFmt", ";scalafmt::test; test:scalafmt::test; sbt:scalafmt::test")
addCommandAlias("runFmt", ";scalafmt; test:scalafmt; sbt:scalafmt")
addCommandAlias("ciBuild", ";checkFmt; clean; test")
