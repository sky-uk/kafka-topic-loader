package integration

import java.util.concurrent.TimeoutException as JavaTimeoutException

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.testkit.scaladsl.TestSink
import base.IntegrationSpecBase
import cats.data.NonEmptyList
import cats.syntax.option.*
import com.typesafe.config.{ConfigException, ConfigFactory}
import io.github.embeddedkafka.Codecs.{stringDeserializer, stringSerializer}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException as KafkaTimeoutException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.scalatest.prop.TableDrivenPropertyChecks.*
import org.scalatest.prop.Tables.Table
import uk.sky.kafka.topicloader.TopicLoader.consumerSettings
import uk.sky.kafka.topicloader.*
import uk.sky.kafka.topicloader.config.Config

import scala.concurrent.Future
import scala.concurrent.duration.*

class TopicLoaderIntSpec extends IntegrationSpecBase {

  "load" when {

    "using LoadAll strategy" should {

      val strategy = LoadAll

      "stream all records from all topics" in new TestContext {
        val topics                 = NonEmptyList.of(testTopic1, testTopic2)
        val (forTopic1, forTopic2) = records(1 to 15).splitAt(10)

        withRunningKafka {
          createCustomTopics(topics)

          publishToKafka(testTopic1, forTopic1)
          publishToKafka(testTopic2, forTopic2)

          val loadedRecords = TopicLoader.load[String, String](topics, strategy).runWith(Sink.seq).futureValue
          loadedRecords.map(recordToTuple) should contain theSameElementsAs (forTopic1 ++ forTopic2)
        }
      }

      "stream available records even when one topic is empty" in new TestContext {
        val topics    = NonEmptyList.of(testTopic1, testTopic2)
        val published = records(1 to 15)

        withRunningKafka {
          createCustomTopics(topics)

          publishToKafka(testTopic1, published)

          val loadedRecords = TopicLoader.load[String, String](topics, strategy).runWith(Sink.seq).futureValue
          loadedRecords.map(recordToTuple) should contain theSameElementsAs published
        }
      }
    }

    "using LoadCommitted strategy" should {

      val strategy = LoadCommitted

      "stream all records up to the committed offset with LoadCommitted strategy" in new TestContext
        with KafkaConsumer {
        val topics                    = NonEmptyList.one(testTopic1)
        val (committed, notCommitted) = records(1 to 15).splitAt(10)

        withRunningKafka {
          createCustomTopics(topics)

          publishToKafka(testTopic1, committed)
          moveOffsetToEnd(testTopic1)
          publishToKafka(testTopic1, notCommitted)

          val loaded = TopicLoader.load[String, String](topics, strategy).runWith(Sink.seq).futureValue
          loaded.map(recordToTuple) should contain theSameElementsAs committed
        }
      }

      "stream available records even when one topic is empty" in new TestContext with KafkaConsumer {
        val topics    = NonEmptyList.of(testTopic1, testTopic2)
        val published = records(1 to 15)

        withRunningKafka {
          createCustomTopics(topics)

          publishToKafka(testTopic1, published)
          moveOffsetToEnd(testTopic1)

          val loadedRecords = TopicLoader.load[String, String](topics, strategy).runWith(Sink.seq).futureValue
          loadedRecords.map(recordToTuple) should contain theSameElementsAs published
        }
      }

      "work when highest offset is missing in log and there are messages after highest offset" in new TestContext
        with KafkaConsumer {
        val published                 = records(1 to 10)
        val (notUpdated, toBeUpdated) = published.splitAt(5)

        withRunningKafka {
          createCustomTopic(testTopic1, aggressiveCompactionConfig)

          publishToKafka(testTopic1, published)
          moveOffsetToEnd(testTopic1)
          publishToKafkaAndWaitForCompaction(testTopic1, toBeUpdated.map { case (k, v) => (k, v.reverse) })

          val loadedRecords =
            TopicLoader.load[String, String](NonEmptyList.one(testTopic1), strategy).runWith(Sink.seq).futureValue

          loadedRecords.map(recordToTuple) should contain theSameElementsAs notUpdated
        }
      }
    }

    "using any strategy" should {

      val loadStrategy = Table("strategy", LoadAll, LoadCommitted)

      "complete successfully if the topic is empty" in new TestContext {
        val topics = NonEmptyList.one(testTopic1)

        withRunningKafka {
          createCustomTopics(topics)

          forEvery(loadStrategy) { strategy =>
            TopicLoader.load[String, String](topics, strategy).runWith(Sink.seq).futureValue shouldBe empty
          }
        }
      }

      "read partitions that have been compacted" in new TestContext with KafkaConsumer {
        val published        = records(1 to 10)
        val publishedUpdated = published.map { case (k, v) => (k, v.reverse) }

        forEvery(loadStrategy) { strategy =>
          withRunningKafka {
            createCustomTopic(testTopic1, aggressiveCompactionConfig)

            publishToKafkaAndWaitForCompaction(testTopic1, published ++ publishedUpdated)

            val loadedRecords =
              TopicLoader.load[String, String](NonEmptyList.one(testTopic1), strategy).runWith(Sink.seq).futureValue
            loadedRecords
              .map(recordToTuple) should contain noElementsOf published
          }
        }
      }
    }

    "Kafka is misbehaving" should {

      "fail if unavailable at startup" in new TestContext {
        override implicit lazy val system: ActorSystem = ActorSystem(
          "test-actor-system",
          ConfigFactory.parseString(
            """
            |akka.kafka.consumer.kafka-clients {
            |  bootstrap.servers = "localhost:6001"
            |  request.timeout.ms = 700
            |  fetch.max.wait.ms = 500
            |  session.timeout.ms = 500
            |  heartbeat.interval.ms = 300
            |  default.api.timeout.ms = 1000
            |}
          """.stripMargin
          )
        )

        TopicLoader
          .load[String, String](NonEmptyList.one(testTopic1), LoadAll)
          .to(Sink.ignore)
          .run()
          .failed
          .futureValue shouldBe a[KafkaTimeoutException]
      }

      "fail if the stream is idle for longer than configured" in new TestContext {

        override implicit lazy val system: ActorSystem = ActorSystem(
          "test-actor-system",
          ConfigFactory.parseString(
            s"""
               |topic-loader {
               |  idle-timeout = 1 second
               |}
               |akka.kafka.consumer {
               |  kafka-clients {
               |    bootstrap.servers = "localhost:${kafkaConfig.kafkaPort}"
               |  }
               |}
             """.stripMargin
          )
        )

        val published    = records(1 to 10)
        val timingOutKey = published.drop(5).head._1

        withRunningKafka {
          createCustomTopic(testTopic1)
          publishToKafka(testTopic1, published)

          TopicLoader
            .load[String, String](NonEmptyList.one(testTopic1), LoadAll)
            .runWith(Sink.foreachAsync(1) { message =>
              if (message.key == timingOutKey) {
                Future.never
              } else {
                Future.unit
              }
            })
            .failed
            .futureValue shouldBe a[JavaTimeoutException]
        }
      }

//      "fail if it goes down during processing"
//      Testable only when upgrading to >1.0.0: https://github.com/akka/alpakka-kafka/issues/329
//      Same as above, but `timingOutKey` should tear down EmbeddedKafka.
    }
  }

  "loadAndRun" should {

    "execute callback when finished loading and keep streaming" in new TestContext {
      val (preLoad, postLoad) = records(1 to 15).splitAt(10)

      withRunningKafka {
        createCustomTopic(testTopic1)

        publishToKafka(testTopic1, preLoad)

        val ((callback, _), recordsProbe) =
          TopicLoader.loadAndRun[String, String](NonEmptyList.one(testTopic1)).toMat(TestSink.probe)(Keep.both).run()

        recordsProbe.request(preLoad.size.toLong + postLoad.size.toLong)
        recordsProbe.expectNextN(preLoad.size.toLong).map(recordToTuple) shouldBe preLoad

        whenReady(callback) { _ =>
          publishToKafka(testTopic1, postLoad)

          recordsProbe.expectNextN(postLoad.size.toLong).map(recordToTuple) shouldBe postLoad
        }
      }
    }
  }

  "partitionedLoad" should {

    val strategy = LoadAll

    "stream records only from required partitions" in new TestContext {
      val (forPart1, forPart2) = records(1 to 15).splitAt(10)
      val topics               = NonEmptyList.one(testTopic1)
      val topicPartition       = new TopicPartition(testTopic1, 1)

      withRunningKafka {
        createCustomTopics(topics, partitions = 5)

        forPart1.foreach { case (key, value) =>
          publishToKafka(new ProducerRecord[String, String](testTopic1, 1, key, value))
        }

        forPart2.foreach { case (key, value) =>
          publishToKafka(new ProducerRecord[String, String](testTopic1, 2, key, value))
        }

        val loadedRecords =
          TopicLoader.partitionedLoad[String, String](topicPartition, strategy).runWith(Sink.seq).futureValue
        loadedRecords.map(recordToTuple) should contain theSameElementsAs forPart1
      }
    }
  }

  "partitionedLoadAndRun" should {

    "execute callback when finished loading a partition and keep streaming" in new TestContext {
      val (preLoad, postLoad) = records(1 to 15).splitAt(10)
      val topicPartition      = new TopicPartition(testTopic1, 1)

      withRunningKafka {
        createCustomTopic(testTopic1, partitions = 5)

        preLoad.foreach { case (key, value) =>
          publishToKafka(new ProducerRecord[String, String](testTopic1, 1, key, value))
        }

        val ((callback, _), recordsProbe) =
          TopicLoader.partitionedLoadAndRun[String, String](topicPartition).toMat(TestSink.probe)(Keep.both).run()

        recordsProbe.request(preLoad.size.toLong + postLoad.size.toLong)
        recordsProbe.expectNextN(preLoad.size.toLong).map(recordToTuple) shouldBe preLoad

        whenReady(callback) { _ =>
          postLoad.foreach { case (key, value) =>
            publishToKafka(new ProducerRecord[String, String](testTopic1, 1, key, value))
          }

          recordsProbe.expectNextN(postLoad.size.toLong).map(recordToTuple) shouldBe postLoad
        }
      }
    }
  }

  "consumerSettings" should {
    implicit val system: ActorSystem = ActorSystem("test")

    "use given ConsumerSettings when given some settings" in {
      val testSettings: ConsumerSettings[Array[Byte], Array[Byte]] =
        ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
          .withProperty("test", "testing")

      val result: ConsumerSettings[Array[Byte], Array[Byte]] =
        consumerSettings(testSettings.some)

      result.properties("test") shouldBe "testing"
    }

    "use default ConsumerSettings if given None for maybeConsumerSettings" in {
      val result: ConsumerSettings[Array[Byte], Array[Byte]] =
        consumerSettings(None)

      result.properties.get("test") shouldBe None
    }
  }

  "config" should {

    "load a valid config correctly" in new TestContext {

      override implicit lazy val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 1 second
             |  buffer-size = 10
             |}
             """.stripMargin
        )
      )

      val config = Config.loadOrThrow(system.settings.config)
      config.topicLoader.idleTimeout shouldBe 1.second
      config.topicLoader.bufferSize.value shouldBe 10
    }

    "fail to load an invalid config" in new TestContext {
      override implicit lazy val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 9999999999999999999999 seconds
             |  buffer-size = -1
             |}
             """.stripMargin
        )
      )

      val exception: ConfigException = intercept[ConfigException](Config.loadOrThrow(system.settings.config))

      exception.getMessage should (
        include(
          "Invalid value at 'topic-loader.idle-timeout': Could not parse duration number '9999999999999999999999'"
        ) and include("Invalid value at 'topic-loader.buffer-size': -1 is not a positive Int")
      )
    }
  }

}
