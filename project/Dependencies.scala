import sbt._

object Dependencies {

  val KafkaVersion = "2.8.0"

  object Akka {
    val akka          = "2.6.16"
    val stream        = "com.typesafe.akka" %% "akka-stream"         % akka
    val streamKafka   = "com.typesafe.akka" %% "akka-stream-kafka"   % "2.1.1"
    val streamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % akka % Test
    val testkit       = "com.typesafe.akka" %% "akka-testkit"        % akka % Test
    val base          = Seq(stream, streamKafka)
    val test          = Seq(streamTestkit, testkit)
  }

  object Cats {
    val cats   = "2.6.1"
    val core   = "org.typelevel" %% "cats-core"   % cats
    val kernal = "org.typelevel" %% "cats-kernel" % cats
    val all    = Seq(core, kernal)
  }

  object Refined {
    val refined    = "0.9.27"
    val base       = "eu.timepit" %% "refined"            % refined
    val pureconfig = "eu.timepit" %% "refined-pureconfig" % refined
    val all        = Seq(base, pureconfig)
  }

  val kafkaClients          = "org.apache.kafka"            % "kafka-clients"           % KafkaVersion
  val scalaLogging          = "com.typesafe.scala-logging" %% "scala-logging"           % "3.9.4"
  val pureconfig            = "com.github.pureconfig"      %% "pureconfig"              % "0.17.0"
  val scalaCollectionCompat = "org.scala-lang.modules"     %% "scala-collection-compat" % "2.5.0"

  val embeddedKafka = "io.github.embeddedkafka" %% "embedded-kafka" % KafkaVersion % Test
  val scalaTest     = "org.scalatest"           %% "scalatest"      % "3.2.9"      % Test

  val core = Akka.base ++ Cats.all ++ Refined.all ++ Seq(
    kafkaClients,
    scalaLogging,
    pureconfig,
    scalaCollectionCompat
  )
  val test = Akka.test ++ Seq(embeddedKafka, scalaTest)
  val all  = core ++ test

}
