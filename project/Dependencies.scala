import sbt._

object Dependencies {

  object Plugins {
    val organizeImports = "com.github.liancheng" %% "organize-imports" % "0.6.0"
  }

  object Akka {
    private val version = "2.6.19"
    val stream          = "com.typesafe.akka" %% "akka-stream"         % version cross CrossVersion.for3Use2_13
    val streamKafka     = "com.typesafe.akka" %% "akka-stream-kafka"   % "2.1.1" cross CrossVersion.for3Use2_13
    val streamTestkit   = "com.typesafe.akka" %% "akka-stream-testkit" % version % Test cross CrossVersion.for3Use2_13
    val testkit         = "com.typesafe.akka" %% "akka-testkit"        % version % Test cross CrossVersion.for3Use2_13
    val base            = Seq(stream, streamKafka)
    val test            = Seq(streamTestkit, testkit)
  }

  object Cats {
    private val version = "2.7.0"
    val core            = "org.typelevel" %% "cats-core"   % version
    val kernal          = "org.typelevel" %% "cats-kernel" % version
    val all             = Seq(core, kernal)
  }

  val kafkaClients          = "org.apache.kafka"            % "kafka-clients"           % "3.1.1"
  val scalaLogging          = "com.typesafe.scala-logging" %% "scala-logging"           % "3.9.4"
  val logbackClassic        = "ch.qos.logback"              % "logback-classic"         % "1.2.11" % Runtime
  val scalaCollectionCompat = "org.scala-lang.modules"     %% "scala-collection-compat" % "2.7.0"

  val embeddedKafka = "io.github.embeddedkafka" %% "embedded-kafka" % "3.2.0"  % Test cross CrossVersion.for3Use2_13
  val scalaTest     = "org.scalatest"           %% "scalatest"      % "3.2.12" % Test

  val scala3Exclusions = Seq(
    "com.typesafe.scala-logging" % "scala-logging_2.13",
    "org.scala-lang.modules"     % "scala-collection-compat_2.13"
  )

  val core = Akka.base ++ Cats.all ++ Seq(
    kafkaClients,
    scalaLogging,
    logbackClassic,
    scalaCollectionCompat
  )
  val test = Akka.test ++ Seq(embeddedKafka, scalaTest)
  val all  = core ++ test

}
