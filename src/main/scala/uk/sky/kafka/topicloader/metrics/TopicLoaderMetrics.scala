package uk.sky.kafka.topicloader.metrics

import org.apache.kafka.clients.consumer.ConsumerRecord

trait TopicLoaderMetrics {

  def onRecord[K, V](record: ConsumerRecord[K, V]): Unit

  def onLoading(): Unit

  def onLoaded(): Unit

  def onError(): Unit

}

object TopicLoaderMetrics {
  def noOp(): TopicLoaderMetrics = new TopicLoaderMetrics {
    override def onRecord[K, V](record: ConsumerRecord[K, V]): Unit = ()

    override def onLoading(): Unit = ()

    override def onLoaded(): Unit = ()

    override def onError(): Unit = ()
  }
}
