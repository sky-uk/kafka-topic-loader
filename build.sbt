lazy val kafkaTopicLoader = Project(id = "kafka-topic-loader", base = file("."))
  .settings(
    organization := "com.sky",
    scalaVersion := "2.12.6",
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
    scalafmtVersion := "1.2.0",
    scalafmtOnCompile := true,
    libraryDependencies ++= deps,
    bintraySettings,
    parallelExecution in Test := false,
    fork in Test := true
  )

lazy val bintraySettings = Seq(
  bintrayOrganization := Some("sky-uk"),
  bintrayReleaseOnPublish in ThisBuild := false,
  bintrayRepository := "oss-maven",
  bintrayVcsUrl := Some("https://github.com/sky-uk/kafka-topic-loader"),
  licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))
)

val AkkaVersion    = "2.5.10"
val CatsVersion    = "1.4.0"
val RefinedVersion = "0.9.0"

// @formatter:off
lazy val deps = Seq(
  "com.typesafe.akka"           %% "akka-stream"              % AkkaVersion,
  "com.typesafe.akka"           %% "akka-stream-kafka"        % "0.22",
  "com.typesafe.scala-logging"  %% "scala-logging"            % "3.7.2",
  "org.typelevel"               %% "cats-core"                % CatsVersion,
  "org.typelevel"               %% "cats-kernel"              % CatsVersion,
  "eu.timepit"                  %% "refined"                  % RefinedVersion,
  "eu.timepit"                  %% "refined-pureconfig"       % RefinedVersion,
  "com.typesafe.akka"           %% "akka-testkit"             % AkkaVersion   % Test,
  "org.scalatest"               %% "scalatest"                % "3.0.5"       % Test,
  "net.manub"                   %% "scalatest-embedded-kafka" % "1.1.0"       % Test
)
// @formatter:on

addCommandAlias("checkFmt", ";scalafmt::test; test:scalafmt::test; sbt:scalafmt::test")
addCommandAlias("runFmt", ";scalafmt; test:scalafmt; sbt:scalafmt")
