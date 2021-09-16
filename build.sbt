organization := "com.sky"
scalaVersion := "3.0.2"
name         := "kafka-topic-loader"

crossScalaVersions := Seq("3.0.2")

// format: off
ThisBuild / scalacOptions ++= {
  if (scalaBinaryVersion.value == "3") {
    Seq(
      "-deprecation",
      "-encoding", "utf8",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings"
    )
  } else {
    Seq(
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
  }
}
// format: on

scalafmtOnCompile := true

Test / parallelExecution := false
Test / fork              := true

releaseCrossBuild := true
licenses += ("BSD New", url("https://opensource.org/licenses/BSD-3-Clause"))

val AkkaVersion  = "2.6.16"
val CatsVersion  = "2.6.1"
val KafkaVersion = "2.8.0"

// @formatter:off
libraryDependencies ++= Seq(
  "com.typesafe.akka"           %% "akka-stream"              % AkkaVersion cross CrossVersion.for3Use2_13,
  "com.typesafe.akka"           %% "akka-stream-kafka"        % "2.1.1" cross CrossVersion.for3Use2_13,
  "org.apache.kafka"             % "kafka-clients"            % KafkaVersion,
  "com.typesafe.scala-logging"  %% "scala-logging"            % "3.9.4",
  "org.typelevel"               %% "cats-core"                % CatsVersion,
  "org.typelevel"               %% "cats-kernel"              % CatsVersion,
  "com.github.pureconfig"       %% "pureconfig-core"               % "0.16.0",
  "org.scala-lang.modules"      %% "scala-collection-compat"  % "2.5.0",
  "com.typesafe.akka"           %% "akka-stream-testkit"      % AkkaVersion   % Test cross CrossVersion.for3Use2_13,
  "com.typesafe.akka"           %% "akka-testkit"             % AkkaVersion   % Test cross CrossVersion.for3Use2_13,
  "org.scalatest"               %% "scalatest"                % "3.2.9"       % Test,
  "io.github.embeddedkafka"     %% "embedded-kafka"           % KafkaVersion  % Test cross CrossVersion.for3Use2_13
)
// @formatter:on

excludeDependencies ++= {
  if (scalaBinaryVersion.value == "3" )
    Seq(
      "com.typesafe.scala-logging" % "scala-logging_2.13",
      "org.scala-lang.modules"     % "scala-collection-compat_2.13"
    )
  else Seq.empty
}

resolvers ++= Seq("segence" at "https://dl.bintray.com/segence/maven-oss-releases/")

addCommandAlias("checkFmt", ";scalafmt::test; test:scalafmt::test; sbt:scalafmt::test")
addCommandAlias("runFmt", ";scalafmt; test:scalafmt; sbt:scalafmt")
addCommandAlias("ciBuild", ";checkFmt; clean; +test")
