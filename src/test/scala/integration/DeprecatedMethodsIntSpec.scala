package integration

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import base.IntegrationSpecBase
import cats.data.NonEmptyList
import io.github.embeddedkafka.Codecs.{stringDeserializer, stringSerializer}
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import uk.sky.kafka.topicloader._

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

@deprecated("Remove when deprecated methods are gone", "")
class DeprecatedMethodsIntSpec extends IntegrationSpecBase {

  "fromTopics" should {
    "execute onRecord for all messages in provided topics" in new TestContext {
      val store                          = new RecordStore
      val (recordsTopic1, recordsTopic2) = records(1 to 30).splitAt(15)
      val topics                         = NonEmptyList.of(testTopic1, testTopic2)

      withRunningKafka {
        createCustomTopics(topics)
        publishToKafka(testTopic1, recordsTopic1)
        publishToKafka(testTopic2, recordsTopic2)

        TopicLoader
          .fromTopics(LoadAll, topics, store.storeRecord, stringDeserializer)
          .runWith(Sink.ignore)
          .futureValue shouldBe Done

        val processedRecords = store.getRecords.futureValue.map(recordToTuple)
        processedRecords should contain theSameElementsAs (recordsTopic1 ++ recordsTopic2)
      }
    }

    "emit last offsets consumed" in new TestContext with KafkaConsumer {
      withRunningKafka {
        createCustomTopic(testTopic1, partitions = 5)
        publishToKafka(testTopic1, records(1 to 15))

        val highestOffsets = withAssignedConsumer(autoCommit = false, offsetReset = "latest", testTopic1) { consumer =>
          val tp = consumer.partitionsFor(testTopic1).asScala.map(pi => new TopicPartition(pi.topic, pi.partition))
          consumer.endOffsets(tp.asJava).asScala
        }

        TopicLoader
          .fromTopics[String](LoadAll, NonEmptyList.one(testTopic1), _ => Future.unit, stringDeserializer)
          .runWith(Sink.seq)
          .futureValue should contain theSameElementsAs Seq(highestOffsets)
      }
    }

    "emit highest offsets even when not consumed anything" in new TestContext with KafkaConsumer {
      withRunningKafka {
        createCustomTopic(testTopic1, partitions = 5)

        val lowestOffsets = withAssignedConsumer(autoCommit = false, offsetReset = "latest", testTopic1) { consumer =>
          val tp = consumer.partitionsFor(testTopic1).asScala.map(pi => new TopicPartition(pi.topic, pi.partition))
          consumer.beginningOffsets(tp.asJava).asScala
        }

        TopicLoader
          .fromTopics[String](LoadCommitted, NonEmptyList.one(testTopic1), _ => ???, stringDeserializer)
          .runWith(Sink.seq)
          .futureValue should contain theSameElementsAs Seq(lowestOffsets)
      }
    }

    "fail when store record is unsuccessful" in new TestContext {
      val exception                                                     = new Exception("boom!")
      val failingHandler: ConsumerRecord[String, String] => Future[Int] =
        _ => Future.failed(exception)

      withRunningKafka {
        createCustomTopics(NonEmptyList.one(testTopic1))

        publishToKafka(testTopic1, records(1 to 15))

        TopicLoader
          .fromTopics[String](LoadAll, NonEmptyList.one(testTopic1), failingHandler, stringDeserializer)
          .runWith(Sink.seq)
          .failed
          .futureValue shouldBe exception
      }
    }
  }

  "fromPartitions" should {
    "load data only from required partitions" in new TestContext with KafkaConsumer {
      val recordsToPublish = records(1 to 15)
      val partitionsToRead = NonEmptyList.of(1, 2)
      val topicPartitions  = partitionsToRead.map(p => new TopicPartition(testTopic1, p))

      withRunningKafka {
        createCustomTopic(testTopic1, partitions = 5)
        publishToKafka(testTopic1, recordsToPublish)
        moveOffsetToEnd(testTopic1)

        val store = new RecordStore()

        TopicLoader
          .fromPartitions(LoadAll, topicPartitions, store.storeRecord, stringDeserializer)
          .runWith(Sink.ignore)
          .futureValue shouldBe Done

        store.getRecords.futureValue.map(_.partition).toSet should contain theSameElementsAs partitionsToRead.toList
      }
    }
  }

  class RecordStore()(implicit system: ActorSystem) {
    private val storeActor = system.actorOf(Props(new Store))

    def storeRecord(rec: ConsumerRecord[String, String])(implicit timeout: Timeout): Future[Int] =
      (storeActor ? rec).mapTo[Int]

    def getRecords(implicit timeout: Timeout): Future[List[ConsumerRecord[String, String]]] =
      (storeActor ? Symbol("GET")).mapTo[List[ConsumerRecord[String, String]]]

    private class Store extends Actor {
      override def receive: Receive = store(List.empty)

      def store(records: List[ConsumerRecord[_, _]]): Receive = {
        case r: ConsumerRecord[_, _] =>
          val newRecs = records :+ r
          sender() ! newRecs.size
          context.become(store(newRecs))
        case Symbol("GET")           =>
          sender() ! records
      }
    }
  }
}
