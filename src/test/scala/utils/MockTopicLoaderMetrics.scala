package utils

import java.util.concurrent.atomic.AtomicInteger

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import uk.sky.kafka.topicloader.metrics.TopicLoaderMetrics
import utils.MockTopicLoaderMetrics.*

import scala.collection.concurrent.TrieMap

class MockTopicLoaderMetrics extends TopicLoaderMetrics {
  val recordCounter = new AtomicInteger()

  val loadingState = TrieMap.empty[TopicPartition, State]

  override def onRecord[K, V](record: ConsumerRecord[K, V]): Unit = recordCounter.incrementAndGet()

  override def onLoading(topicPartition: TopicPartition): Unit =
    loadingState.put(topicPartition, Loading(topicPartition))

  override def onLoaded(topicPartition: TopicPartition): Unit =
    loadingState.put(topicPartition, Loaded(topicPartition))

  override def onError(topicPartition: TopicPartition): Unit =
    loadingState.put(topicPartition, ErrorLoading(topicPartition))
}

object MockTopicLoaderMetrics {
  sealed trait State extends Product with Serializable

  case class Loading(topicPartition: TopicPartition)      extends State
  case class Loaded(topicPartition: TopicPartition)       extends State
  case class ErrorLoading(topicPartition: TopicPartition) extends State
}
