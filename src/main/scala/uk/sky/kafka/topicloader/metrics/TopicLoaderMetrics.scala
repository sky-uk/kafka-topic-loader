package uk.sky.kafka.topicloader.metrics

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition

trait TopicLoaderMetrics {

  def onRecord[K, V](record: ConsumerRecord[K, V]): Unit

  def onLoading(topicPartitions: TopicPartition): Unit

  def onLoaded(topicPartitions: TopicPartition): Unit

  def onError(topicPartitions: TopicPartition): Unit

}

object TopicLoaderMetrics {
  def noOp(): TopicLoaderMetrics = new TopicLoaderMetrics {
    override def onRecord[K, V](record: ConsumerRecord[K, V]): Unit = ()

    override def onLoading(topicPartitions: TopicPartition): Unit = ()

    override def onLoaded(topicPartitions: TopicPartition): Unit = ()

    override def onError(topicPartitions: TopicPartition): Unit = ()
  }
}
