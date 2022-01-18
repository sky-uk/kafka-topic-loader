import sbt._

object Dependencies {

  object Akka {
    private val version = "2.6.16"
    val stream          = "com.typesafe.akka" %% "akka-stream"         % version
    val streamKafka     = "com.typesafe.akka" %% "akka-stream-kafka"   % "2.1.1"
    val streamTestkit   = "com.typesafe.akka" %% "akka-stream-testkit" % version % Test
    val testkit         = "com.typesafe.akka" %% "akka-testkit"        % version % Test
    val base            = Seq(stream, streamKafka)
    val test            = Seq(streamTestkit, testkit)
  }

  object Cats {
    private val version = "2.6.1"
    val core            = "org.typelevel" %% "cats-core"   % version
    val kernal          = "org.typelevel" %% "cats-kernel" % version
    val all             = Seq(core, kernal)
  }

  object Refined {
    private val version = "0.9.27"
    val base            = "eu.timepit" %% "refined"            % version
    val pureconfig      = "eu.timepit" %% "refined-pureconfig" % version
    val all             = Seq(base, pureconfig)
  }

  val kafkaClients          = "org.apache.kafka"            % "kafka-clients"           % "2.8.0"
  val scalaLogging          = "com.typesafe.scala-logging" %% "scala-logging"           % "3.9.4"
  val logbackClassic        = "ch.qos.logback"              % "logback-classic"         % "1.2.10" % Runtime
  val pureconfig            = "com.github.pureconfig"      %% "pureconfig"              % "0.17.0"
  val scalaCollectionCompat = "org.scala-lang.modules"     %% "scala-collection-compat" % "2.5.0"

  val embeddedKafka = "io.github.embeddedkafka" %% "embedded-kafka" % "2.8.0" % Test
  val scalaTest     = "org.scalatest"           %% "scalatest"      % "3.2.9" % Test

  val core = Akka.base ++ Cats.all ++ Refined.all ++ Seq(
    kafkaClients,
    scalaLogging,
    logbackClassic,
    pureconfig,
    scalaCollectionCompat
  )
  val test = Akka.test ++ Seq(embeddedKafka, scalaTest)
  val all  = core ++ test

}
