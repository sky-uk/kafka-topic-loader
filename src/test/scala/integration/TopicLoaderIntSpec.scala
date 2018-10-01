package integration

import java.lang
import java.lang.{Long => JLong}
import java.util.UUID
import java.util.concurrent.{TimeoutException => JTimeoutException}

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{TestActor, TestProbe}
import akka.util.Timeout
import cats.data.NonEmptyList
import cats.syntax.option._
import com.sky.kafka.topicloader._
import com.sky.kafka.{LoadAll, LoadCommitted, LoadTopicStrategy}
import com.typesafe.config.ConfigFactory
import net.manub.embeddedkafka.Codecs.stringSerializer
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.clients.consumer._
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.{CommonClientConfigs, consumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.serialization._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.prop.Tables.Table
import org.scalatest.{Matchers, Suite, WordSpecLike}
import utils.RandomPort

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class TopicLoaderIntSpec
    extends WordSpecLike
    with Matchers
    with ScalaFutures
    with Eventually {

  override implicit val patienceConfig = PatienceConfig(5.seconds, 100.millis)

  "Retrieve state on start up" should {

    "update store records with entire state of provided topics using full log strategy" in new TestContext
    with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> Long.box(_))

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2),
                           partitions = 5)

        publishToKafka(LoadStateTopic1, records)
        publishToKafka(LoadStateTopic2, records)

        whenReady(loadTestTopic(LoadAll, storeRecord))(_ =>
          numRecordsLoaded shouldBe 30)
      }
    }

    "update store records with state of provided topics using last consumer commit strategy" in new TestContext
    with KafkaConsumer with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> Long.box(_))

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2),
                           partitions = 5)

        publishToKafka(LoadStateTopic1, records.take(11))
        moveOffsetToEnd(LoadStateTopic1)
        publishToKafka(LoadStateTopic1, records.takeRight(4))

        whenReady(loadTestTopic(LoadCommitted, storeRecord))(_ =>
          numRecordsLoaded shouldBe 11)
      }
    }

    "update store records when one of state topics is empty for load all strategy" in new TestContext
    with KafkaConsumer with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> Long.box(_))

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2),
                           partitions = 5)

        publishToKafka(LoadStateTopic1, records)

        whenReady(loadTestTopic(LoadAll, storeRecord))(_ =>
          numRecordsLoaded shouldBe 15)
      }
    }

    "update store records when one of state topics is empty for load committed strategy" in new TestContext
    with KafkaConsumer with CountingRecordStore {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> Long.box(_))

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2),
                           partitions = 5)

        publishToKafka(LoadStateTopic1, records)
        moveOffsetToEnd(LoadStateTopic1)

        whenReady(loadTestTopic(LoadCommitted, storeRecord))(_ =>
          numRecordsLoaded shouldBe 15)
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

    "read always from LOG-BEGINNING-OFFSETS" in new TestContext
    with KafkaConsumer {
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> Long.box(_))

      forEvery(loadStrategy) { strategy =>
        withRunningKafka {
          createCustomTopic(LoadStateTopic1, partitions = 5)
          publishToKafka(LoadStateTopic1, records)

          val count = countKafkaRecordsFromEarliestOffset(LoadStateTopic1,
                                                          autoCommit = true)
          count shouldBe records.size

          loadTestTopic(strategy).futureValue shouldBe Done
        }
      }
    }

    "work when LOG-BEGINNING-OFFSETS is > 0 (e.g. has been reset)" in new TestContext
    with KafkaConsumer {
      val consumerGroupId = "version-src-consumer"
      val records =
        (1 to 15).toList.map(UUID.randomUUID().toString -> Long.box(_))
      val topicConfig =
        Map("cleanup.policy" -> "delete", "retention.ms" -> "10")

      forEvery(loadStrategy) { strategy =>
        withRunningKafka {
          createCustomTopic(LoadStateTopic1, topicConfig, partitions = 5)
          records.foreach {
            case (key, msg) => publishToKafka(LoadStateTopic1, key, msg)
          }

          waitForKafkaTopicToHaveMaxElementsOf(LoadStateTopic1, 10)

          loadTestTopic(strategy).futureValue shouldBe Done
        }
      }
    }

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

    "fail the source if Kafka goes down during processing" in new TestContext
    with DelayedProbe {

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
          (1 to 5).toList.map(UUID.randomUUID().toString -> Long.box(_))

        createCustomTopic(LoadStateTopic1, partitions = 12)
        publishToKafka(LoadStateTopic1, records)

        val callSlowProbe: ConsumerRecord[String, JLong] => Future[
          Option[ConsumerRecord[String, JLong]]] =
          cr => (slowProbe.ref ? 123).map(_ => cr.some)

        val res = loadTestTopic(LoadAll, callSlowProbe)

        slowProbe.expectMsgType[Int](10.seconds)

        res
      }
      result.failed.futureValue shouldBe a[JTimeoutException]
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
      val failingHandler
        : ConsumerRecord[String, JLong] => Future[Option[Unit]] =
        _ => Future.failed(boom)

      withRunningKafka {
        createCustomTopics(List(LoadStateTopic1, LoadStateTopic2),
                           partitions = 5)

        publishToKafka(LoadStateTopic1,
                       List(UUID.randomUUID().toString -> Long.box(1)))

        loadTestTopic(LoadAll, failingHandler).failed.futureValue shouldBe boom
      }
    }
  }

  trait TestContext extends EmbeddedKafka with Suite {

    implicit val timeout = Timeout(5 seconds)
    implicit val longSerializer = new LongSerializer

    implicit lazy val kafkaConfig =
      EmbeddedKafkaConfig(kafkaPort = RandomPort(),
                          zooKeeperPort = RandomPort(),
                          Map("log.roll.ms" -> "10"))

    implicit lazy val system: ActorSystem = ActorSystem(
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
           |        group.id = test-idzxcv-consumer-group
           |        client.id = test-idzxcv-consumer-group
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

    implicit lazy val ec: ExecutionContext = system.dispatcher
    implicit lazy val mat: ActorMaterializer = ActorMaterializer()

    def config(strategy: LoadTopicStrategy = LoadAll) =
      TopicLoaderConfig(strategy,
                        NonEmptyList.of(LoadStateTopic1, LoadStateTopic2),
                        1.second)

    val LoadStateTopic1 = "load-state-topic-1"
    val LoadStateTopic2 = "load-state-topic-2"

    val loadStrategy = Table("strategy", LoadAll, LoadCommitted)

    def loadTestTopic(strategy: LoadTopicStrategy,
                      f: ConsumerRecord[String, JLong] => Future[Option[Any]] =
                        cr => Future.successful(cr.some)) =
      TopicLoader(config(strategy), f, new LongDeserializer)
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
            Thread.sleep(delay.toMillis); sender ! response;
            TestActor.KeepRunning
      })
      probe
    }
  }

  trait CountingRecordStore { this: TestContext =>
    var numRecordsLoaded = 0

    val storeRecord: ConsumerRecord[String, JLong] => Future[Option[Unit]] =
      _ =>
        Future
          .successful { numRecordsLoaded = numRecordsLoaded + 1 }
          .map(_.some)
  }

  trait KafkaConsumer { this: TestContext =>

    def moveOffsetToEnd(topics: String*): Unit = {
      val consumer = getConsumer(autoCommit = true, "latest")
      consumer.subscribe(topics.toList.asJava)
      consumer.poll(0)
      consumer.close()
    }

    def waitForKafkaTopicToHaveMaxElementsOf(topic: String, amount: Int) =
      eventually {
        countKafkaRecordsFromEarliestOffset(topic, autoCommit = false) should be <= amount
      }

    def countKafkaRecordsFromEarliestOffset(topic: String, autoCommit: Boolean)(
        implicit system: ActorSystem): Int = {

      val consumer = getConsumer(autoCommit, offsetReset = "earliest")
      val partitions = consumer
        .partitionsFor(topic)
        .asScala
        .map(p => new TopicPartition(topic, p.partition))

      try {
        consumer.assign(partitions.asJava)
        consumer.poll(5000).records(topic).asScala.size
      } finally {
        consumer.close()
      }
    }

    private def getConsumer(
        autoCommit: Boolean,
        offsetReset: String): Consumer[String, lang.Long] = {
      val consumerCfg: Map[String, Object] = Map(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> s"localhost:${kafkaConfig.kafkaPort}",
//        ConsumerConfig.GROUP_ID_CONFIG -> "test-consumer-group",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> autoCommit.toString,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> offsetReset
      )

      new consumer.KafkaConsumer(consumerCfg.asJava,
                                 new StringDeserializer,
                                 new LongDeserializer)
    }
  }
}
