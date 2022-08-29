package integration

import java.util.concurrent.TimeoutException as JavaTimeoutException
import akka.Done
import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.kafka.{ConsumerSettings, TopicPartitionsAssigned, TopicPartitionsRevoked}
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import base.IntegrationSpecBase
import cats.data.NonEmptyList
import cats.syntax.option.*
import com.typesafe.config.{ConfigException, ConfigFactory}
import io.github.embeddedkafka.Codecs.{stringDeserializer, stringSerializer}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException as KafkaTimeoutException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.scalatest.prop.TableDrivenPropertyChecks.*
import org.scalatest.prop.Tables.Table
import uk.sky.kafka.topicloader.TopicLoader.consumerSettings
import uk.sky.kafka.topicloader.*
import uk.sky.kafka.topicloader.config.Config

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

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

      "stream all records from all topics and emit a source per partition" in new TestContext {
        val topics                         = NonEmptyList.one(testTopic1)
        val (forPartition1, forPartition2) = records(1 to 15).splitAt(10)
        val partitions: Long               = 2

        withRunningKafka {
          createCustomTopics(topics, partitions.toInt)

          publishToKafka(testTopic1, 0, forPartition1)
          publishToKafka(testTopic1, 1, forPartition2)

          val partitionedSources =
            TopicLoader.partitionedLoad[String, String](topics, strategy).take(partitions).runWith(Sink.seq).futureValue

          sourceFromPartition(partitionedSources, 0)
            .runWith(Sink.seq)
            .futureValue
            .map(recordToTuple) should contain theSameElementsAs forPartition1

          sourceFromPartition(partitionedSources, 1)
            .runWith(Sink.seq)
            .futureValue
            .map(recordToTuple) should contain theSameElementsAs forPartition2
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

//    "execute callback when finished loading and keep streaming" in new TestContext {
//      val (preLoad, postLoad) = records(1 to 15).splitAt(10)
//
//      withRunningKafka {
//        createCustomTopic(testTopic1)
//
//        publishToKafka(testTopic1, preLoad)
//
//        val ((callback, _), recordsProbe) =
//          TopicLoader.loadAndRun[String, String](NonEmptyList.one(testTopic1)).toMat(TestSink.probe)(Keep.both).run()
//
//        recordsProbe.request(preLoad.size.toLong + postLoad.size.toLong)
//        recordsProbe.expectNextN(preLoad.size.toLong).map(recordToTuple) shouldBe preLoad
//
//        whenReady(callback) { _ =>
//          publishToKafka(testTopic1, postLoad)
//
//          recordsProbe.expectNextN(postLoad.size.toLong).map(recordToTuple) shouldBe postLoad
//        }
//      }
//    }

//    "execute callback when finished loading and keep streaming per partition" in new TestContext {
//      val (preLoadPart1, postLoadPart1) = records(1 to 15).splitAt(10)
//      val (preLoadPart2, postLoadPart2) = records(16 to 30).splitAt(10)
//      val partitions: Long              = 2
//
//      withRunningKafka {
//        createCustomTopic(testTopic1, partitions = partitions.toInt)
//
//        publishToKafka(testTopic1, 0, preLoadPart1)
//        publishToKafka(testTopic1, 1, preLoadPart2)
//
//        val partitionedStream = TopicLoader
//          .partitionedLoadAndRun[String, String](NonEmptyList.one(testTopic1))
//          .take(partitions)
//          .runWith(Sink.seq)
//          .futureValue
//
//        def validate(
//            source: Source[ConsumerRecord[String, String], (Future[Done], Future[Consumer.Control])],
//            partition: Int,
//            preLoad: Seq[(String, String)],
//            postLoad: Seq[(String, String)]
//        ): Unit = {
//
//          val ((callback, _), recordsProbe) = source.toMat(TestSink.probe)(Keep.both).run()
//
//          recordsProbe.request(preLoad.size.toLong + postLoad.size.toLong)
//          recordsProbe.expectNextN(preLoad.size.toLong).map(recordToTuple) shouldBe preLoad
//
//          whenReady(callback) { _ =>
//            publishToKafka(testTopic1, partition, postLoad)
//
//            recordsProbe.expectNextN(postLoad.size.toLong).map(recordToTuple) shouldBe postLoad
//          }
//        }
//
//        validate(sourceFromPartition(partitionedStream, 0), 0, preLoadPart1, postLoadPart1)
//        validate(sourceFromPartition(partitionedStream, 1), 1, preLoadPart2, postLoadPart2)
//      }
//    }
  }

  "partitionedLoadAndRun" should {
    "re-assign a partition to an stream and reload the topic if there are two instances and one of them is killed" in new TestContext {
      // App 1 - partition 1
      // App 2 - partition 2

      // App 1 - killed mid load/run
      // Assert - App 2 loads partition 2, but also when assigned partition 1 loads it up and continues running

      // Setup - write to partition 1&2, load up both 1 and 2
      // Kill app 1
      // Verify app 2 loads the contents of partition 2, and after app 1 dies loads up partition 1

      class App(name: String) {
        val state: TrieMap[String, String] = TrieMap[String, String]()

        val loaded: AtomicBoolean = new AtomicBoolean()

        val assignedPartitions: mutable.HashSet[TopicPartition] = mutable.HashSet[TopicPartition]()

        def stream(topics: NonEmptyList[String]): Source[Future[Done], Consumer.Control] = TopicLoader
          .partitionedLoadAndRun[String, String](topics)
          .map { case (tp, source) =>
            // Assign our partitions to set
            assignedPartitions.add(tp)
            println(s"Partition $tp was assigned to $name")

            // Put our elems in the store, and remove our tp from the set on revoke
            val newSource = source.map { cr =>
              println(s"Got record: ${cr.key()}, ${cr.value()}")
              state.put(cr.key(), cr.value())
              cr
            }
              .watchTermination() { case ((f1, f2), f3) =>
                f1.onComplete(_ => println(s"$name - f1 - onComplete for $tp"))
                f2.onComplete(_ => println(s"$name - f2 - onComplete for $tp"))
                f3.onComplete(_ => println(s"$name - f3 - onComplete for $tp"))
              }
              .runWith(Sink.ignore)
            newSource.onComplete(_ => println("Outer oncomplete"))
            newSource
          }
      }

      val (preLoadPart1, postLoadPart1) = records(1 to 15).splitAt(10)
      val (preLoadPart2, postLoadPart2) = records(16 to 30).splitAt(10)
      val partitions: Long              = 2

      withRunningKafka {
        createCustomTopic(testTopic1, partitions = partitions.toInt)

        publishToKafka(testTopic1, 0, preLoadPart1)
        publishToKafka(testTopic1, 1, preLoadPart2)

        // TODO - assert app 1 & 2 have the correct state
        val app1: App = new App("app1")
        val app2: App = new App("app2")

        val (f1, f2) = app1
          .stream(NonEmptyList.one(testTopic1))
          .toMat(Sink.ignore)(Keep.both)
          .run()

//        app2.stream(NonEmptyList.one(testTopic1)).run()

        eventually {
          app1.loaded.get() shouldBe true
          app1.state.toMap shouldBe (preLoadPart1 ++ preLoadPart2).toMap
        }

        val foo = app2
          .stream(NonEmptyList.one(testTopic1))
          .toMat(Sink.ignore)(Keep.both)
          .run()

        eventually {
          val app2AssignedPart = app2.assignedPartitions.loneElement.partition()
          if (app2AssignedPart == 0) app2.state.toMap shouldBe preLoadPart1.toMap
          else app2.state.toMap shouldBe preLoadPart2.toMap
        }

        publishToKafka(testTopic1, 0, postLoadPart1)
        publishToKafka(testTopic1, 1, postLoadPart2)

        eventually {
          app1.state.toMap shouldBe (preLoadPart1 ++ preLoadPart2 ++ postLoadPart1).toMap
//          app2.state.toMap shouldBe (preLoadPart2 ++ postLoadPart2).toMap
        }
        // TODO - assert carried on loading

        // TODO - kill app 1

        // TODO - assert app 2 loaded up state that app 1, aka pre&post-load for part 1

        // TODO publish to part 1 and 2 and assert app 2 gets both
      }

    }
  }

  "consumerSettings" should {

    val system: ActorSystem = ActorSystem(
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

    "use given ConsumerSettings when given some settings" in {
      val config = Config.loadOrThrow(system.settings.config)

      val testSettings: ConsumerSettings[Array[Byte], Array[Byte]] =
        ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
          .withProperty("test", "testing")

      val result: ConsumerSettings[Array[Byte], Array[Byte]] =
        consumerSettings(testSettings.some, config.topicLoader)(system)

      result.properties("test") shouldBe "testing"
    }

    "use default ConsumerSettings if given None for maybeConsumerSettings" in {
      val config = Config.loadOrThrow(system.settings.config)

      val result: ConsumerSettings[Array[Byte], Array[Byte]] = consumerSettings(None, config.topicLoader)(system)

      result.properties.get("test") shouldBe None
    }

    "use the config client ID over the akka client ID" in {
      implicit val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 1 second
             |  buffer-size = 10
             |  client-id = test-client-id
             |}
             |akka.kafka.consumer.kafka-clients.client.id = akka-client-id
             """.stripMargin
        )
      )

      val config = Config.loadOrThrow(system.settings.config)

      val result: ConsumerSettings[Array[Byte], Array[Byte]] = consumerSettings(None, config.topicLoader)

      result.properties(ConsumerConfig.CLIENT_ID_CONFIG) shouldBe "test-client-id"
    }

    "use the akka client ID if no client ID is specified" in {
      implicit val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 1 second
             |  buffer-size = 10
             |}
             |akka.kafka.consumer.kafka-clients.client.id = akka-client-id
             """.stripMargin
        )
      )

      val config = Config.loadOrThrow(system.settings.config)

      val result: ConsumerSettings[Array[Byte], Array[Byte]] = consumerSettings(None, config.topicLoader)

      result.properties(ConsumerConfig.CLIENT_ID_CONFIG) shouldBe "akka-client-id"
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
             |  client-id = test-client-id
             |}
             """.stripMargin
        )
      )

      val config = Config.loadOrThrow(system.settings.config)
      config.topicLoader.idleTimeout shouldBe 1.second
      config.topicLoader.bufferSize.value shouldBe 10
      config.topicLoader.clientId.value shouldBe "test-client-id"
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
