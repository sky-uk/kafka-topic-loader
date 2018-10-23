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
import base.{AkkaSpecBase, WordSpecBase}
import cats.data.NonEmptyList
import cats.syntax.option._
import com.sky.kafka.topicloader._
import com.typesafe.config.ConfigFactory
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerRecord, _}
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

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

class TopicLoaderIntSpec extends WordSpecBase with Eventually {

  override implicit val patienceConfig = PatienceConfig(20.seconds, 200.millis)

  "Retrieve state on start up" should {

    "update store records with entire state of provided topics using full log strategy" in new TestContext
    with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> _.toString)

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2), partitions = 5)

        publishToKafka(LoadStateTopic1, records)
        publishToKafka(LoadStateTopic2, records)

        whenReady(loadTestTopic(LoadAll, storeRecord))(_ => numRecordsLoaded shouldBe 30)
      }
    }

    "update store records with state of provided topics using last consumer commit strategy" in new TestContext
    with KafkaConsumer with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> _.toString)

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2), partitions = 5)

        publishToKafka(LoadStateTopic1, records.take(11))
        moveOffsetToEnd(LoadStateTopic1)
        publishToKafka(LoadStateTopic1, records.takeRight(4))

        whenReady(loadTestTopic(LoadCommitted, storeRecord))(_ => numRecordsLoaded shouldBe 11)
      }
    }

    "update store records when one of state topics is empty for load all strategy" in new TestContext with KafkaConsumer
    with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> _.toString)

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2), partitions = 5)

        publishToKafka(LoadStateTopic1, records)

        whenReady(loadTestTopic(LoadAll, storeRecord))(_ => numRecordsLoaded shouldBe 15)
      }
    }

    "update store records when one of state topics is empty for load committed strategy" in new TestContext
    with KafkaConsumer with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> _.toString)

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2), partitions = 5)

        publishToKafka(LoadStateTopic1, records)
        moveOffsetToEnd(LoadStateTopic1)

        whenReady(loadTestTopic(LoadCommitted, storeRecord))(_ => numRecordsLoaded shouldBe 15)
      }
    }

    "complete successfully if the versions topic is empty" in new TestContext {
      withRunningKafka {
        createCustomTopic(LoadStateTopic1, partitions = 10)

        forEvery(loadStrategy) { strategy =>
          loadTestTopic(strategy).futureValue shouldBe Done
        }
      }
    }

    "read always from LOG-BEGINNING-OFFSETS" in new TestContext with KafkaConsumer {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> _.toString)

      forEvery(loadStrategy) { strategy =>
        withRunningKafka {
          createCustomTopic(LoadStateTopic1, partitions = 5)
          publishToKafka(LoadStateTopic1, records)

          val count = withAssignedConsumer(true, offsetReset = "earliest", LoadStateTopic1)(
            consumeAllKafkaRecordsFromEarliestOffset(_).size)
          count shouldBe records.size

          loadTestTopic(strategy).futureValue shouldBe Done
        }
      }
    }

    "work when LOG-BEGINNING-OFFSETS is > 0 (e.g. has been reset)" in new TestContext with KafkaConsumer {
      val initialRecords = records(1 to 10, message = "old")
      val newRecords     = records(1 to 10, message = "new")

      forEvery(loadStrategy) { strategy =>
        withRunningKafka {
          createCustomTopic(LoadStateTopic1, compactedTopicConfig, partitions = 2)
          publishToKafka(LoadStateTopic1, initialRecords)
          consumeEventually(LoadStateTopic1)(_ should contain allElementsOf initialRecords)

          publishToKafka(LoadStateTopic1, newRecords)
          publishToKafka(LoadStateTopic1, records(11 to 20, "msg"))
          waitForOverridingKafkaMessages(LoadStateTopic1, initialRecords, newRecords)

          loadTestTopic(strategy).futureValue shouldBe Done
        }
      }
    }

    "work when highest offset is missing in log and there are messages after highest offset" in new TestContext
    with KafkaConsumer {
      val recordsStore             = new RecordStore()
      val initialRecordsToOverride = records(6 to 10, "will be overridden")
      val overridingRecords        = records(6 to 10, "new")

      withRunningKafka {
        createCustomTopic(LoadStateTopic1, compactedTopicConfig, partitions = 2)

        publishToKafka(LoadStateTopic1, records(1 to 5, message = "init"))
        publishToKafka(LoadStateTopic1, initialRecordsToOverride)
        moveOffsetToEnd(LoadStateTopic1)

        publishToKafka(LoadStateTopic1, overridingRecords)
        publishToKafka(LoadStateTopic1, records(11 to 20, message = "new"))
        waitForOverridingKafkaMessages(LoadStateTopic1, initialRecordsToOverride, overridingRecords)

        loadTestTopic(LoadCommitted, recordsStore.storeRecord).futureValue shouldBe Done

        val recordKeys = recordsStore.getRecords.map(_.map(_.key)).futureValue
        recordKeys should contain theSameElementsAs List.range(1, 10)
      }
    }
    //TODO when highest offset is higher then maximum offset in log (for log compacted topic)

    "throw if Kafka is unavailable at startup" in new TestContext {
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

      loadTestTopic(LoadAll).failed.futureValue shouldBe a[TimeoutException]
    }

    "fail the source if Kafka goes down during processing" in new TestContext with DelayedProbe {

      override implicit lazy val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
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

      val slowProbe = delayedProbe(100.millis, Unit)

      val result = withRunningKafka {
        val records =
          (1 to 5).toList.map(UUID.randomUUID().toString -> _.toString)

        createCustomTopic(LoadStateTopic1, partitions = 12)
        publishToKafka(LoadStateTopic1, records)

        val callSlowProbe: ConsumerRecord[String, String] => Future[Int] = _ => (slowProbe.ref ? 123).map(_ => 0)

        val res = loadTestTopic(LoadAll, callSlowProbe)

        slowProbe.expectMsgType[Int](10.seconds)

        res
      }

      result.failed.futureValue shouldBe a[java.util.concurrent.TimeoutException]
    }

    "fail when Kafka is too slow for client to start" in new TestContext {

      override implicit lazy val system: ActorSystem =
        ActorSystem(
          "test-actor-system",
          ConfigFactory.parseString(
            s"""
             |akka.kafka.consumer.kafka-clients {
             |  bootstrap.servers = "localhost:${kafkaConfig.kafkaPort}"
             |  request.timeout.ms = 3
             |  session.timeout.ms = 2
             |  fetch.max.wait.ms = 2
             |  heartbeat.interval.ms = 1
             |}
        """.stripMargin
          )
        )

      withRunningKafka {
        createCustomTopic(LoadStateTopic1, partitions = 5)
        loadTestTopic(LoadAll).failed.futureValue shouldBe a[TimeoutException]
      }
    }

    "fail when store record is unsuccessful" in new TestContext {
      val boom = new Exception("boom!")
      val failingHandler: ConsumerRecord[String, String] => Future[Int] =
        _ => Future.failed(boom)

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2), partitions = 5)

        publishToKafka(LoadStateTopic1, List(UUID.randomUUID().toString -> "1"))

        loadTestTopic(LoadAll, failingHandler).failed.futureValue shouldBe boom
      }
    }
  }

  trait TestContext extends AkkaSpecBase with EmbeddedKafka {

    implicit val timeout          = Timeout(5 seconds)
    implicit val stringSerializer = new StringSerializer

    implicit lazy val kafkaConfig =
      EmbeddedKafkaConfig(kafkaPort = RandomPort(), zooKeeperPort = RandomPort(), Map("log.roll.ms" -> "10"))

    override implicit lazy val system: ActorSystem = ActorSystem(
      name = s"test-actor-system-${UUID.randomUUID().toString}",
      config = ConfigFactory.parseString(
        s"""
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

    val compactedTopicConfig = Map(
      "cleanup.policy"            -> "compact",
      "delete.retention.ms"       -> "0",
      "min.insync.replicas"       -> "2",
      "min.cleanable.dirty.ratio" -> "0.01",
      "segment.ms"                -> "10"
    )

    def records(r: Range, message: String) = r.toList.map(_.toString -> message)

    def config(strategy: LoadTopicStrategy = LoadAll) =
      TopicLoaderConfig(strategy, NonEmptyList.of(LoadStateTopic1, LoadStateTopic2), 1.second, 100)

    val LoadStateTopic1 = "load-state-topic-1"
    val LoadStateTopic2 = "load-state-topic-2"

    val loadStrategy = Table("strategy", LoadAll, LoadCommitted)

    def loadTestTopic(strategy: LoadTopicStrategy,
                      f: ConsumerRecord[String, String] => Future[Int] = cr => Future.successful(0)) =
      TopicLoader(config(strategy), f, new StringDeserializer)
        .runWith(Sink.ignore)

    def createCustomTopics(topics: List[String], partitions: Int) =
      topics.foreach(createCustomTopic(_, partitions = partitions))
  }

  trait DelayedProbe { this: TestContext =>
    def delayedProbe[T](delay: FiniteDuration, response: T) = {
      val probe = TestProbe()
      probe.setAutoPilot((sender: ActorRef, msg: Any) =>
        msg match {
          case _ =>
            Thread.sleep(delay.toMillis); sender ! response
            TestActor.KeepRunning
      })
      probe
    }
  }

  trait CountingRecordStore { this: TestContext =>
    val numRecordsLoaded = new AtomicInteger()

    val storeRecord: ConsumerRecord[String, String] => Future[Int] =
      _ => Future.successful(numRecordsLoaded.incrementAndGet())
  }

  trait KafkaConsumer { this: TestContext =>

    def moveOffsetToEnd(topic: String): Unit = withAssignedConsumer(autoCommit = true, "latest", topic, None)(_.poll(0))

    def waitForOverridingKafkaMessages(topic: String,
                                       initialRecords: List[(String, String)],
                                       newRecords: List[(String, String)]) =
      consumeEventually(topic) { r =>
        r should contain noElementsOf initialRecords
        r should contain allElementsOf newRecords
      }

    def consumeEventually(topic: String, groupId: String = UUID.randomUUID().toString)(
        f: List[(String, String)] => Assertion) =
      eventually {
        val records = withAssignedConsumer(autoCommit = false, offsetReset = "earliest", topic, groupId.some)(
          consumeAllKafkaRecordsFromEarliestOffset(_, List.empty))

        f(records.map(r => r.key -> r.value))
      }

    def withAssignedConsumer[T](autoCommit: Boolean,
                                offsetReset: String,
                                topic: String,
                                groupId: Option[String] = None)(f: Consumer[String, String] => T): T = {
      val consumer = getConsumer(autoCommit, offsetReset, groupId)
      assignPartitions(consumer, topic)
      try {
        f(consumer)
      } finally {
        consumer.close()
      }
    }

    def assignPartitions(consumer: Consumer[_, _], topic: String): Unit = {
      val partitions = consumer.partitionsFor(topic).asScala.map(p => new TopicPartition(topic, p.partition))
      consumer.assign(partitions.asJava)
    }

    @tailrec
    final def consumeAllKafkaRecordsFromEarliestOffset(
        consumer: Consumer[String, String],
        polled: List[ConsumerRecord[String, String]] = List.empty): List[ConsumerRecord[String, String]] = {
      val p = consumer.poll(500).iterator().asScala.toList
      if (p.isEmpty) polled else consumeAllKafkaRecordsFromEarliestOffset(consumer, polled ++ p)
    }

    def getConsumer(autoCommit: Boolean, offsetReset: String, groupId: Option[String]): Consumer[String, String] = {

      val baseSettings =
        ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
          .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit.toString)
          .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset)

      val settings = groupId.fold(baseSettings)(baseSettings.withProperty(ConsumerConfig.GROUP_ID_CONFIG, _))
      settings.createKafkaConsumer()
    }
  }

  class RecordStore()(implicit system: ActorSystem) {
    private val storeActor = system.actorOf(Props(classOf[Store], RecordStore.this))

    def storeRecord(rec: ConsumerRecord[String, String])(implicit timeout: Timeout): Future[Int] =
      (storeActor ? rec).mapTo[Int]

    def getRecords(implicit timeout: Timeout): Future[List[ConsumerRecord[String, String]]] =
      (storeActor ? 'GET).mapTo[List[ConsumerRecord[String, String]]]

    private class Store extends Actor {
      override def receive: Receive = store(List.empty)

      def store(records: List[ConsumerRecord[_, _]]): Receive = {
        case r: ConsumerRecord[_, _] =>
          val newRecs = records :+ r
          sender() ! newRecs.size
          context.become(store(newRecs))
        case 'GET =>
          sender() ! records
      }
    }
  }
}
