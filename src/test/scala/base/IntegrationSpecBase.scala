package base

import java.util.UUID

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.util.Timeout
import cats.data.NonEmptyList
import cats.syntax.option._
import com.typesafe.config.ConfigFactory
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{Consumer, ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.scalatest.Assertion
import org.scalatest.concurrent.Eventually
import net.manub.embeddedkafka.Codecs.{stringDeserializer, stringSerializer}
import utils.RandomPort

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.duration._

abstract class IntegrationSpecBase extends WordSpecBase with Eventually {

  override implicit val patienceConfig = PatienceConfig(20.seconds, 200.millis)

  implicit val timeout = Timeout(5 seconds)

  trait TestContext extends AkkaSpecBase with EmbeddedKafka {

    implicit lazy val kafkaConfig =
      EmbeddedKafkaConfig(kafkaPort = RandomPort(), zooKeeperPort = RandomPort(), Map("log.roll.ms" -> "10"))

    override implicit lazy val system: ActorSystem = ActorSystem(
      name = s"test-actor-system-${UUID.randomUUID().toString}",
      config = ConfigFactory.parseString(
        s"""
           |topic-loader {
           |  idle-timeout = 5 minutes
           |  buffer-size = 1000
           |  parallelism = 5
           |}
           |akka {
           |  loglevel = "OFF"
           |  kafka {
           |    consumer {
           |      max-wakeups = 2
           |      wakeup-timeout = 2 seconds
           |      kafka-clients {
           |        ${CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG} = "localhost:${kafkaConfig.kafkaPort}"
           |        ${ConsumerConfig.AUTO_OFFSET_RESET_CONFIG} = "earliest"
           |        // auto-commit should be set to *false* for at-least-once semantics.  By setting this to true, our tests
           |        // can prove that our code overrides this value, so we can't accidentally & silently break at-least-once
           |        // functionality with bad config
           |        ${ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG} = true
           |        group.id = test-consumer-group
           |      }
           |    }
           |    producer.kafka-clients {
           |      ${CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG} = "localhost:${kafkaConfig.kafkaPort}"
           |      ${ProducerConfig.MAX_BLOCK_MS_CONFIG} = 3000
           |    }
           |  }
           |}
        """.stripMargin
      )
    )

    val aggressiveCompactionConfig = Map(
      "cleanup.policy"            -> "compact",
      "delete.retention.ms"       -> "0",
      "min.cleanable.dirty.ratio" -> "0.01",
      "segment.ms"                -> "10"
    )

    def records(r: Seq[Int]): Seq[(String, String)] = r.map(i => s"k$i" -> s"v$i")

    def recordToTuple[K, V](record: ConsumerRecord[K, V]): (K, V) = (record.key(), record.value())

    val testTopic1          = "load-state-topic-1"
    val testTopic2          = "load-state-topic-2"
    val testTopicPartitions = 5

    def createCustomTopics(topics: NonEmptyList[String], partitions: Int = testTopicPartitions): Unit =
      topics.map(createCustomTopic(_, partitions = partitions))

    /*
     * Note: Compaction is only triggered if messages are published as a separate statement.
     */
    def publishToKafkaAndTriggerCompaction(topic: String, messages: Seq[(String, String)]): Unit = {
      val fillerSize = 20
      val filler     = Stream.continually(UUID.randomUUID().toString).take(fillerSize).map(x => (x, x))

      publishToKafka(topic, messages)
      publishToKafka(topic, filler)
    }
  }

  trait KafkaConsumer { this: TestContext =>

    def publishToKafkaAndWaitForCompaction(topic: String, messages: Seq[(String, String)]): Unit = {
      publishToKafkaAndTriggerCompaction(topic, messages)
      waitForCompaction(testTopic1)
    }

    def moveOffsetToEnd(topic: String): Unit =
      withAssignedConsumer(autoCommit = true, "latest", topic, None)(_.poll(0))

    def waitForCompaction(topic: String): Assertion =
      consumeEventually(topic) { r =>
        val messageKeys = r.map { case (k, _) => k }
        messageKeys should contain theSameElementsAs messageKeys.toSet
      }

    def consumeEventually(topic: String, groupId: String = UUID.randomUUID().toString)(
        f: List[(String, String)] => Assertion): Assertion =
      eventually {
        val records = withAssignedConsumer(autoCommit = false, offsetReset = "earliest", topic, groupId.some)(
          consumeAllKafkaRecordsFromEarliestOffset(_, List.empty))

        f(records.map(r => r.key -> r.value))
      }

    def withAssignedConsumer[T](autoCommit: Boolean,
                                offsetReset: String,
                                topic: String,
                                groupId: Option[String] = None)(f: Consumer[String, String] => T): T = {
      val consumer   = createConsumer(autoCommit, offsetReset, groupId)
      val partitions = consumer.partitionsFor(topic).asScala.map(p => new TopicPartition(topic, p.partition))
      consumer.assign(partitions.asJava)
      try {
        f(consumer)
      } finally {
        consumer.close()
      }
    }

    @tailrec
    final def consumeAllKafkaRecordsFromEarliestOffset(
        consumer: Consumer[String, String],
        polled: List[ConsumerRecord[String, String]] = List.empty): List[ConsumerRecord[String, String]] = {
      val p = consumer.poll(500).iterator().asScala.toList
      if (p.isEmpty) polled else consumeAllKafkaRecordsFromEarliestOffset(consumer, polled ++ p)
    }

    def createConsumer(autoCommit: Boolean, offsetReset: String, groupId: Option[String]): Consumer[String, String] = {

      val baseSettings =
        ConsumerSettings(system, stringDeserializer, stringDeserializer)
          .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit.toString)
          .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset)

      val settings = groupId.fold(baseSettings)(baseSettings.withProperty(ConsumerConfig.GROUP_ID_CONFIG, _))
      settings.createKafkaConsumer()
    }
  }

}
