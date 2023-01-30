package utils

import org.apache.kafka.clients.consumer.ConsumerRecord
import uk.sky.kafka.topicloader.metrics.TopicLoaderMetrics
import utils.MockTopicLoaderMetrics._

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

class MockTopicLoaderMetrics extends TopicLoaderMetrics {
  val recordCounter = new AtomicInteger()

  val loadingState = new AtomicReference[State]()

  override def onRecord[K, V](record: ConsumerRecord[K, V]): Unit = recordCounter.incrementAndGet()

  override def onLoading(): Unit = loadingState.set(Loading)

  override def onLoaded(): Unit = loadingState.set(Loaded)

  override def onError(): Unit = loadingState.set(Error)
}

object MockTopicLoaderMetrics {
  sealed trait State extends Product with Serializable

  case object Loading extends State
  case object Loaded  extends State
  case object Error   extends State
}
