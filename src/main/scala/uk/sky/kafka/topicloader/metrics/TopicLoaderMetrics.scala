package uk.sky.kafka.topicloader.metrics

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition

trait TopicLoaderMetrics {

  def onRecord[K, V](record: ConsumerRecord[K, V]): Unit

  def onLoading(topicPartition: TopicPartition): Unit

  def onLoaded(topicPartition: TopicPartition): Unit

  def onError(topicPartition: TopicPartition): Unit

}

object TopicLoaderMetrics {
  def noOp(): TopicLoaderMetrics = new TopicLoaderMetrics {
    override def onRecord[K, V](record: ConsumerRecord[K, V]): Unit = ()

    override def onLoading(topicPartition: TopicPartition): Unit = ()

    override def onLoaded(topicPartition: TopicPartition): Unit = ()

    override def onError(topicPartition: TopicPartition): Unit = ()
  }
}
