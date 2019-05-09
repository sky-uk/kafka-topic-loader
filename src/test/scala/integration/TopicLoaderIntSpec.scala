package integration

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.kafka.ConsumerSettings
import akka.stream.scaladsl.Sink
import akka.testkit.{TestActor, TestProbe}
import akka.pattern.ask
import akka.util.Timeout
import base.{AkkaSpecBase, IntegrationSpecBase, WordSpecBase}
import cats.data.NonEmptyList
import cats.syntax.option._
import cats.syntax.functor._
import com.sky.kafka.topicloader._
import com.typesafe.config.ConfigFactory
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer._
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.serialization._
import org.scalatest.concurrent.Eventually
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.prop.Tables.Table
import utils.RandomPort
import eu.timepit.refined.auto._
import org.apache.kafka.common.TopicPartition
import org.scalatest.Assertion
import net.manub.embeddedkafka.Codecs.stringSerializer

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import cats.implicits.catsStdInstancesForList

import scala.collection.mutable.MutableList

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

    "???" should {

      "fail if Kafka is unavailable at startup" in new TestContext {
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
            |}
          """.stripMargin
          )
        )

        TopicLoader
          .load[String, String](NonEmptyList.one(testTopic1), LoadAll)
          .to(Sink.ignore)
          .run()
          .failed
          .futureValue shouldBe a[TimeoutException]
      }

      "fail if Kafka goes down during processing" in new TestContext {

        override implicit lazy val system: ActorSystem = ActorSystem(
          "test-actor-system",
          ConfigFactory.parseString(
            s"""
               |topic-loader {
               |  idle-timeout = 10 second
               |  buffer-size = 1
               |}
               |akka {
               |  loglevel = "OFF"
               |  log-config-on-start = off
               |
               |  kafka.consumer {
               |    max-wakeups = 2
               |    kafka-clients {
               |      fetch.max.bytes = 1
               |      bootstrap.servers = "localhost:${kafkaConfig.kafkaPort}"
               |    }
               |  }
               |}
             """.stripMargin
          )
        )

        val published    = records(1 to 100)
        val timingOutKey = published.drop(5).head._1

        val broker = EmbeddedKafka.start()
        //withRunningKafka {
        createCustomTopic(testTopic1)

        publishToKafka(testTopic1, published)

        TopicLoader
          .load[String, String](NonEmptyList.one(testTopic1), LoadAll)
          .to(Sink.foreachAsync(1) { message =>
            println(message)
            if (message.key() == timingOutKey) {
              println("STOPPING THE THING")
              Future {
                broker.stop(clearLogs = true)
              }
            } else
              Future.unit
          })
          .run()
          .failed
          .futureValue shouldBe a[TimeoutException]
      }
      // }
    }
  }

//  "TopicLoader" should {
//
//
//

//
//    "fail when Kafka is too slow for client to start" in new TestContext {
//
//      override implicit lazy val system: ActorSystem =
//        ActorSystem(
//          "test-actor-system",
//          ConfigFactory.parseString(
//            s"""
//             |akka.kafka.consumer.kafka-clients {
//             |  bootstrap.servers = "localhost:${kafkaConfig.kafkaPort}"
//             |  request.timeout.ms = 3
//             |  session.timeout.ms = 2
//             |  fetch.max.wait.ms = 2
//             |  heartbeat.interval.ms = 1
//             |}
//        """.stripMargin
//          )
//        )
//
//      withRunningKafka {
//        createCustomTopic(LoadStateTopic1, partitions = 5)
//        loadTestTopic(LoadAll).failed.futureValue shouldBe a[TimeoutException]
//      }
//    }
//
//    "fail when store record is unsuccessful" in new TestContext {
//      val boom = new Exception("boom!")
//      val failingHandler: ConsumerRecord[String, String] => Future[Int] =
//        _ => Future.failed(boom)
//
//      withRunningKafka {
//        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2), partitions = 5)
//
//        publishToKafka(LoadStateTopic1, List(UUID.randomUUID().toString -> "1"))
//
//        loadTestTopic(LoadAll, failingHandler).failed.futureValue shouldBe boom
//      }
//    }
//
//    "emit last offsets consumed by topic loader" in new TestContext with KafkaConsumer {
//      val partitions       = 1 to 5 map (partitionNumber => new TopicPartition(LoadStateTopic1, partitionNumber - 1))
//      val recordsToPublish = records(1 to 15, UUID.randomUUID().toString)
//
//      withRunningKafka {
//        createCustomTopic(LoadStateTopic1, partitions = partitions.size)
//
//        publishToKafka(LoadStateTopic1, recordsToPublish)
//
//        withAssignedConsumer(false, "latest", LoadStateTopic1) { consumer =>
//          val highestOffsets = partitions.toList.fproduct(consumer.position).toMap
//          val loaded         = new MutableList[Boolean]
//
//          testTopicLoader(LoadAll, NonEmptyList.one(LoadStateTopic1), _ => { loaded += false; Future.unit })
//            .map(offsets => { loaded += true; offsets })
//            .runWith(Sink.seq)
//            .futureValue should contain theSameElementsAs Seq(highestOffsets)
//
//          loaded.last shouldBe true
//        }
//      }
//    }
//
//    "emit highest offsets even when not consumed anything" in new TestContext with KafkaConsumer {
//      val partitions = 1 to 5 map (partitionNumber => new TopicPartition(LoadStateTopic1, partitionNumber - 1))
//      withRunningKafka {
//        createCustomTopic(LoadStateTopic1, partitions = partitions.size)
//
//        withAssignedConsumer(false, "latest", LoadStateTopic1) { consumer =>
//          val highestOffsets = partitions.toList.fproduct(consumer.position).toMap
//
//          testTopicLoader(LoadAll, NonEmptyList.one(LoadStateTopic1), _ => Future.unit)
//            .runWith(Sink.head)
//            .futureValue shouldBe highestOffsets
//        }
//      }
//    }
//  }
}
