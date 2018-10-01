import sbt._

object Dependencies {

  val AkkaVersion = "2.5.10"
  val CatsVersion = "1.4.0"

  // @formatter:off
  lazy val deps = Seq(
    "org.scalatest"               %% "scalatest"                % "3.0.5",
    "com.typesafe.akka"           %% "akka-stream"              % AkkaVersion,
    "com.typesafe.akka"           %% "akka-testkit"             % AkkaVersion   % Test,
    "com.typesafe.akka"           %% "akka-stream-testkit"      % AkkaVersion   % Test,
    "com.typesafe.akka"           %% "akka-stream-kafka"        % "0.22",
    "com.typesafe.scala-logging"  %% "scala-logging"            % "3.7.2",
    "org.typelevel"               %% "cats-core"                % CatsVersion,
    "org.typelevel"               %% "cats-kernel"              % CatsVersion,
    "net.manub"                   %% "scalatest-embedded-kafka" % "1.1.0"       % Test
  )
  // @formatter:on
}
