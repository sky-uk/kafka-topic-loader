package integration

import java.util.concurrent.TimeoutException as JavaTimeoutException

import base.IntegrationSpecBase
import cats.data.NonEmptyList
import com.typesafe.config.ConfigFactory
import io.github.embeddedkafka.Codecs.{stringDeserializer, stringSerializer}
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.common.errors.TimeoutException as KafkaTimeoutException
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringSerializer}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.prop.TableDrivenPropertyChecks.*
import org.scalatest.prop.Tables.Table
import uk.sky.kafka.topicloader.*
import utils.RandomPort

import scala.concurrent.Future

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

    "Kafka consumer settings" should {

      "Override default settings and run two topic loaders on different cluster" in new TestContext {
        val topics                 = NonEmptyList.of(testTopic1, testTopic2)
        val (forTopic1, forTopic2) = records(1 to 15).splitAt(10)
        val stringSerializer       = new StringSerializer

        val secondKafkaConfig = EmbeddedKafkaConfig(RandomPort(), RandomPort())
        val consumerSettings  = ConsumerSettings
          .create(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
          .withBootstrapServers(s"localhost:${secondKafkaConfig.kafkaPort}")

        EmbeddedKafka.start()(secondKafkaConfig)

        withRunningKafka {

          createCustomTopics(topics)
          createCustomTopics(topics)(secondKafkaConfig)

          publishToKafka(testTopic1, forTopic1)(secondKafkaConfig, stringSerializer, stringSerializer)
          publishToKafka(testTopic2, forTopic2)(secondKafkaConfig, stringSerializer, stringSerializer)

          val loadedRecords       = TopicLoader.load[String, String](topics, LoadAll).runWith(Sink.seq).futureValue
          val secondLoadedRecords =
            TopicLoader.load[String, String](topics, LoadAll, Some(consumerSettings)).runWith(Sink.seq).futureValue

          loadedRecords.map(recordToTuple) shouldBe empty
          secondLoadedRecords.map(recordToTuple) should contain theSameElementsAs (forTopic1 ++ forTopic2)
        }

        EmbeddedKafka.stop()
      }
    }

    "Kafka is misbehaving" should {

      "fail if unavailable at startup" in new TestContext {
        override implicit lazy val system: ActorSystem = ActorSystem(
          "test-actor-system",
          ConfigFactory.parseString(
            """
            |pekko.kafka.consumer.kafka-clients {
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
               |pekko.kafka.consumer {
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

}
