import sbt._

object Dependencies {

  object Plugins {
    val organizeImports = "com.github.liancheng" %% "organize-imports" % "0.6.0"
  }

  object Pekko {
    private val version = "1.1.3"
    val stream          = "org.apache.pekko" %% "pekko-stream"           % version cross CrossVersion.for3Use2_13
    val streamKafka     = "org.apache.pekko" %% "pekko-connectors-kafka" % "1.1.0" cross CrossVersion.for3Use2_13
    val streamTestkit   = "org.apache.pekko" %% "pekko-stream-testkit"   % version % Test cross CrossVersion.for3Use2_13
    val testkit         = "org.apache.pekko" %% "pekko-testkit"          % version % Test cross CrossVersion.for3Use2_13
    val base            = Seq(stream, streamKafka)
    val test            = Seq(streamTestkit, testkit)
  }

  object Cats {
    private val version = "2.7.0"
    val core            = "org.typelevel" %% "cats-core"   % version
    val kernal          = "org.typelevel" %% "cats-kernel" % version
    val all             = Seq(core, kernal)
  }

  val kafkaClients          = "org.apache.kafka"            % "kafka-clients"           % "3.4.0"
  val scalaLogging          = "com.typesafe.scala-logging" %% "scala-logging"           % "3.9.5"
  val logbackClassic        = "ch.qos.logback"              % "logback-classic"         % "1.4.7" % Runtime
  val scalaCollectionCompat = "org.scala-lang.modules"     %% "scala-collection-compat" % "2.11.0"

  val embeddedKafka = "io.github.embeddedkafka" %% "embedded-kafka" % "3.4.0.1" % Test cross CrossVersion.for3Use2_13
  val scalaTest     = "org.scalatest"           %% "scalatest"      % "3.2.15"  % Test

  val scala3Exclusions = Seq(
    "com.typesafe.scala-logging" % "scala-logging_2.13",
    "org.scala-lang.modules"     % "scala-collection-compat_2.13"
  )

  val core = Pekko.base ++ Cats.all ++ Seq(
    kafkaClients,
    scalaLogging,
    logbackClassic,
    scalaCollectionCompat
  )
  val test = Pekko.test ++ Seq(embeddedKafka, scalaTest)
  val all  = core ++ test

}
