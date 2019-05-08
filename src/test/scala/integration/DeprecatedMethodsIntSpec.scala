package integration

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import base.IntegrationSpecBase
import cats.data.NonEmptyList
import com.sky.kafka.topicloader._
import net.manub.embeddedkafka.Codecs.stringSerializer
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization._
import org.scalatest.prop.TableDrivenPropertyChecks._

import scala.concurrent.Future

@deprecated("Remove when deprecated methods are gone", "")
class DeprecatedMethodsIntSpec extends IntegrationSpecBase {

  "fromTopics" should {
    "execute onRecord for all messages in provided topics" in new TestContext {
      val store                          = new RecordStore()
      val (recordsTopic1, recordsTopic2) = records(1 to 30).splitAt(15)
      val topics                         = NonEmptyList.of(testTopic1, testTopic2)
      val loadingStream                  = TopicLoader.fromTopics(LoadAll, topics, store.storeRecord, stringDeserializer)

      withRunningKafka {
        createCustomTopics(topics, partitions = 5)
        publishToKafka(testTopic1, recordsTopic1)
        publishToKafka(testTopic2, recordsTopic2)

        loadingStream.runWith(Sink.ignore).futureValue shouldBe Done

        val processedRecords = store.getRecords.futureValue.map(recordToTuple)
        processedRecords should contain theSameElementsAs (recordsTopic1 ++ recordsTopic2)
      }
    }
  }

  "fromPartitions" should {
    "load data only from required partitions" in new TestContext with KafkaConsumer {
      val recordsToPublish = records(1 to 15)
      val partitionsToRead = NonEmptyList.of(1, 2)
      val topicPartitions  = partitionsToRead.map(p => new TopicPartition(testTopic1, p))

      withRunningKafka {
        createCustomTopics(NonEmptyList.one(testTopic1), partitions = 5)
        publishToKafka(testTopic1, recordsToPublish)
        moveOffsetToEnd(testTopic1)

        val store = new RecordStore()

        val loadingStream =
          TopicLoader.fromPartitions(LoadAll, topicPartitions, store.storeRecord, stringDeserializer)

        loadingStream.runWith(Sink.ignore).futureValue shouldBe Done

        store.getRecords.futureValue.map(_.partition) should contain only (partitionsToRead.toList: _*)
      }
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
