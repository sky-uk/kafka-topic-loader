package utils

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import org.apache.kafka.clients.consumer.ConsumerRecord
import uk.sky.kafka.topicloader.metrics.TopicLoaderMetrics
import utils.MockTopicLoaderMetrics._

class MockTopicLoaderMetrics extends TopicLoaderMetrics {
  val recordCounter = new AtomicInteger()

  val loadingState = new AtomicReference[State](NotStarted)

  override def onRecord[K, V](record: ConsumerRecord[K, V]): Unit = recordCounter.incrementAndGet()

  override def onLoading(): Unit = loadingState.set(Loading)

  override def onLoaded(): Unit = loadingState.set(Loaded)

  override def onError(): Unit = loadingState.set(ErrorLoading)
}

object MockTopicLoaderMetrics {
  sealed trait State extends Product with Serializable

  case object NotStarted   extends State
  case object Loading      extends State
  case object Loaded       extends State
  case object ErrorLoading extends State
}
