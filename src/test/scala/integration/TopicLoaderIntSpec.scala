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

    "using LoadAll stragegy" should {

      val strategy = LoadAll

      "stream all records from all topics" in new TestContext {
        val topics                 = NonEmptyList.of(testTopic1, testTopic2)
        val (forTopic1, forTopic2) = records(1 to 15).splitAt(10)

        withRunningKafka {
          createCustomTopics(topics, partitions = 5)

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
          createCustomTopics(topics, partitions = 5)

          publishToKafka(testTopic1, published)

          val loadedRecords = TopicLoader.load[String, String](topics, strategy).runWith(Sink.seq).futureValue
          loadedRecords.map(recordToTuple) should contain theSameElementsAs published
        }
      }
    }

    "using LoadCommitted stragegy" should {

      val strategy = LoadCommitted

      "stream all records up to the committed offset with LoadCommitted strategy" in new TestContext
      with KafkaConsumer {
        val topics                    = NonEmptyList.one(testTopic1)
        val (committed, notCommitted) = records(1 to 15).splitAt(10)

        withRunningKafka {
          createCustomTopics(topics, partitions = 5)

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
          createCustomTopics(topics, partitions = 5)

          publishToKafka(testTopic1, published)
          moveOffsetToEnd(testTopic1)

          val loadedRecords = TopicLoader.load[String, String](topics, strategy).runWith(Sink.seq).futureValue
          loadedRecords.map(recordToTuple) should contain theSameElementsAs published
        }
      }
    }
  }

//  "TopicLoader" should {

//    "complete successfully if the topic is empty" in new TestContext {
//      withRunningKafka {
//        createCustomTopic(LoadStateTopic1, partitions = 10)
//
//        forEvery(loadStrategy) { strategy =>
//          loadTestTopic(strategy).futureValue shouldBe Done
//        }
//      }
//    }
//
//    "read always from LOG-BEGINNING-OFFSETS" in new TestContext with KafkaConsumer {
//      val recordsToPublish = records(1 to 15, UUID.randomUUID().toString)
//
//      forEvery(loadStrategy) { strategy =>
//        withRunningKafka {
//          createCustomTopic(LoadStateTopic1, partitions = 5)
//          publishToKafka(LoadStateTopic1, recordsToPublish)
//
//          val count = withAssignedConsumer(true, offsetReset = "earliest", LoadStateTopic1)(
//            consumeAllKafkaRecordsFromEarliestOffset(_).size)
//          count shouldBe recordsToPublish.size
//
//          loadTestTopic(strategy).futureValue shouldBe Done
//        }
//      }
//    }
//
//    "work when LOG-BEGINNING-OFFSETS is > 0 (e.g. has been reset)" in new TestContext with KafkaConsumer {
//      val initialRecords = records(1 to 10, message = "old")
//      val newRecords     = records(1 to 10, message = "new")
//      val endRecords     = records(11 to 20, message = "msg")
//
//      forEvery(loadStrategy) { strategy =>
//        val recordStore = new RecordStore()
//
//        withRunningKafka {
//          createCustomTopic(LoadStateTopic1, compactedTopicConfig, partitions = 2)
//          publishToKafka(LoadStateTopic1, initialRecords)
//          publishToKafka(LoadStateTopic1, newRecords)
//
//          publishToKafka(LoadStateTopic1, endRecords)
//          moveOffsetToEnd(LoadStateTopic1)
//          waitForOverridingKafkaMessages(LoadStateTopic1, initialRecords, newRecords)
//
//          loadTestTopic(strategy, recordStore.storeRecord).futureValue shouldBe Done
//          recordStore.recordKeys.futureValue should contain theSameElementsAs (newRecords ++ endRecords).toMap.keys
//        }
//      }
//    }
//
//    "work when highest offset is missing in log and there are messages after highest offset" in new TestContext
//    with KafkaConsumer {
//      val recordStore              = new RecordStore()
//      val initialRecordsToStay     = records(1 to 5, "init")
//      val initialRecordsToOverride = records(6 to 10, "will be overridden")
//      val overridingRecords        = records(6 to 10, "new")
//
//      withRunningKafka {
//        createCustomTopic(LoadStateTopic1, compactedTopicConfig, partitions = 2)
//
//        publishToKafka(LoadStateTopic1, initialRecordsToStay ++ initialRecordsToOverride)
//        moveOffsetToEnd(LoadStateTopic1)
//
//        publishToKafka(LoadStateTopic1, overridingRecords)
//        publishToKafka(LoadStateTopic1, records(11 to 20, "new"))
//        waitForOverridingKafkaMessages(LoadStateTopic1, initialRecordsToOverride, overridingRecords)
//
//        loadTestTopic(LoadCommitted, recordStore.storeRecord).futureValue shouldBe Done
//
//        val recordKeys = recordStore.getRecords.map(_.map(_.key)).futureValue
//        recordKeys should contain theSameElementsAs initialRecordsToStay.map(_._1)
//      }
//    }
//
//    "throw if Kafka is unavailable at startup" in new TestContext {
//      override implicit lazy val system: ActorSystem = ActorSystem(
//        "test-actor-system",
//        ConfigFactory.parseString(
//          """
//          |akka.kafka.consumer.kafka-clients {
//          |  bootstrap.servers = "localhost:6001"
//          |  request.timeout.ms = 700
//          |  fetch.max.wait.ms = 500
//          |  session.timeout.ms = 500
//          |  heartbeat.interval.ms = 300
//          |}
//        """.stripMargin
//        )
//      )
//
//      loadTestTopic(LoadAll).failed.futureValue shouldBe a[TimeoutException]
//    }
//
//    "fail the source if Kafka goes down during processing" in new TestContext with DelayedProbe {
//
//      override implicit lazy val system: ActorSystem = ActorSystem(
//        "test-actor-system",
//        ConfigFactory.parseString(
//          s"""
//           |topic-loader {
//           |  idle-timeout = 1 second
//           |  buffer-size = 1
//           |  parallelism = 2
//           |}
//           |akka {
//           |  loglevel = "OFF"
//           |  log-config-on-start = off
//           |
//           |  kafka.consumer {
//           |    max-wakeups = 2
//           |    kafka-clients {
//           |      fetch.max.bytes = 1
//           |      bootstrap.servers = "localhost:${kafkaConfig.kafkaPort}"
//           |    }
//           |  }
//           |}
//      """.stripMargin
//        )
//      )
//
//      val slowProbe = delayedProbe(100.millis, Unit)
//
//      val result = withRunningKafka {
//        val recordsToPublish = records(1 to 15, UUID.randomUUID().toString)
//
//        createCustomTopic(LoadStateTopic1, partitions = 12)
//        publishToKafka(LoadStateTopic1, recordsToPublish)
//
//        val callSlowProbe: ConsumerRecord[String, String] => Future[Int] = _ => (slowProbe.ref ? 123).map(_ => 0)
//
//        val res = loadTestTopic(LoadAll, callSlowProbe)
//
//        slowProbe.expectMsgType[Int](10.seconds)
//
//        res
//      }
//
//      result.failed.futureValue shouldBe a[java.util.concurrent.TimeoutException]
//    }
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
