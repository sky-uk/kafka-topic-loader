package uk.sky.kafka.topicloader

import java.lang.{Long => JLong}
import java.util.{List => JList, Map => JMap, Optional}

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Keep, Source}
import akka.{Done, NotUsed}
import cats.data.NonEmptyList
import cats.syntax.bifunctor._
import cats.syntax.option._
import cats.syntax.show._
import cats.{Bifunctor, Show}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization._
import uk.sky.kafka.topicloader.config.{Config, TopicLoaderConfig}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

object TopicLoader extends TopicLoader with DeprecatedMethods {
  private[topicloader] case class LogOffsets(lowest: Long, highest: Long)

  private case class HighestOffsetsWithRecord[K, V](
      partitionOffsets: Map[TopicPartition, Long],
      consumerRecord: Option[ConsumerRecord[K, V]] = none[ConsumerRecord[K, V]]
  )

  private implicit class DeserializerOps(private val bytes: Array[Byte]) extends AnyVal {
    def deserialize[T](topic: String)(implicit ds: Deserializer[T]): T = ds.deserialize(topic, bytes)
  }

  private implicit val crBiFunctor: Bifunctor[ConsumerRecord] = new Bifunctor[ConsumerRecord] {
    override def bimap[A, B, C, D](fab: ConsumerRecord[A, B])(f: A => C, g: B => D): ConsumerRecord[C, D] =
      new ConsumerRecord[C, D](
        fab.topic,
        fab.partition,
        fab.offset,
        fab.timestamp,
        fab.timestampType,
        fab.serializedKeySize,
        fab.serializedValueSize,
        f(fab.key),
        g(fab.value),
        fab.headers,
        Optional.empty[Integer]
      )
  }

  private object WithRecord {
    def unapply[K, V](h: HighestOffsetsWithRecord[K, V]): Option[ConsumerRecord[K, V]] = h.consumerRecord
  }

  private implicit val showLogOffsets: Show[LogOffsets] = o =>
    s"LogOffsets(lowest = ${o.lowest}, highest = ${o.highest})"

  private implicit val showTopicPartitions: Show[Iterable[TopicPartition]] =
    _.map(tp => s"${tp.topic}:${tp.partition}").mkString(", ")
}

trait TopicLoader extends LazyLogging {

  import TopicLoader._

  /** Source that loads the specified topics from the beginning and completes when the offsets reach the point specified
    * by the requested strategy. Materializes to a Future[Consumer.Control] where the Future represents the retrieval of
    * offsets and the Consumer.Control the Kafka consumer stream.
    */
  def load[K : Deserializer, V : Deserializer](
      topics: NonEmptyList[String],
      strategy: LoadTopicStrategy,
      maybeConsumerSettings: Option[ConsumerSettings[Array[Byte], Array[Byte]]] = None
  )(implicit system: ActorSystem): Source[ConsumerRecord[K, V], Future[Consumer.Control]] = {
    val config =
      Config
        .loadOrThrow(system.settings.config)
        .topicLoader
    load(logOffsetsForTopics(topics, strategy), config, maybeConsumerSettings)
  }

  /** Source that loads the specified topics from the beginning. When the latest current offsets are reached, the
    * materialised value is completed, and the stream continues.
    */
  def loadAndRun[K : Deserializer, V : Deserializer](
      topics: NonEmptyList[String],
      maybeConsumerSettings: Option[ConsumerSettings[Array[Byte], Array[Byte]]] = None
  )(implicit system: ActorSystem): Source[ConsumerRecord[K, V], (Future[Done], Future[Consumer.Control])] = {
    val config            = Config.loadOrThrow(system.settings.config).topicLoader
    val logOffsetsF       = logOffsetsForTopics(topics, LoadAll)
    val postLoadingSource = Source.futureSource(logOffsetsF.map { logOffsets =>
      val highestOffsets = logOffsets.map { case (p, o) => p -> o.highest }
      kafkaSource[K, V](highestOffsets, config, maybeConsumerSettings)
    }(system.dispatcher))

    load[K, V](logOffsetsF, config, maybeConsumerSettings)
      .watchTermination()(Keep.right)
      .concatMat(postLoadingSource)(Keep.both)
  }

  protected def logOffsetsForPartitions(topicPartitions: NonEmptyList[TopicPartition], strategy: LoadTopicStrategy)(
      implicit system: ActorSystem
  ): Future[Map[TopicPartition, LogOffsets]] =
    fetchLogOffsets(_ => topicPartitions.toList, strategy)

  protected def logOffsetsForTopics(topics: NonEmptyList[String], strategy: LoadTopicStrategy)(implicit
      system: ActorSystem
  ): Future[Map[TopicPartition, LogOffsets]] = {
    val partitionsFromTopics: Consumer[Array[Byte], Array[Byte]] => List[TopicPartition] = c =>
      for {
        t <- topics.toList
        p <- c.partitionsFor(t).asScala
      } yield new TopicPartition(t, p.partition)
    fetchLogOffsets(partitionsFromTopics, strategy)
  }

  private def fetchLogOffsets(
      f: Consumer[Array[Byte], Array[Byte]] => List[TopicPartition],
      strategy: LoadTopicStrategy
  )(implicit system: ActorSystem): Future[Map[TopicPartition, LogOffsets]] = {
    def earliestOffsets(
        consumer: Consumer[Array[Byte], Array[Byte]],
        beginningOffsets: Map[TopicPartition, Long]
    ): Map[TopicPartition, Long] =
      beginningOffsets.map { case (p, o) =>
        p -> Option(consumer.committed(Set(p).asJava).get(p)).fold(o)(_.offset)
      }

    import system.dispatcher

    Future {
      withStandaloneConsumer(consumerSettings(None)) { c =>
        val offsets          = offsetsFrom(f(c)) _
        val beginningOffsets = offsets(c.beginningOffsets)
        val endOffsets       = strategy match {
          case LoadAll       => offsets(c.endOffsets)
          case LoadCommitted => earliestOffsets(c, beginningOffsets)
        }

        beginningOffsets.map { case (k, v) =>
          k -> LogOffsets(v, endOffsets(k))
        }
      }
    }
  }

  protected def load[K : Deserializer, V : Deserializer](
      logOffsets: Future[Map[TopicPartition, LogOffsets]],
      config: TopicLoaderConfig,
      maybeConsumerSettings: Option[ConsumerSettings[Array[Byte], Array[Byte]]]
  )(implicit system: ActorSystem): Source[ConsumerRecord[K, V], Future[Consumer.Control]] = {

    def topicDataSource(offsets: Map[TopicPartition, LogOffsets]): Source[ConsumerRecord[K, V], Consumer.Control] = {
      offsets.foreach { case (partition, offset) => logger.info(s"${offset.show} for $partition") }

      val nonEmptyOffsets   = offsets.filter { case (_, o) => o.highest > o.lowest }
      val lowestOffsets     = nonEmptyOffsets.map { case (p, o) => p -> o.lowest }
      val allHighestOffsets =
        HighestOffsetsWithRecord[K, V](nonEmptyOffsets.map { case (p, o) => p -> (o.highest - 1) })

      val filterBelowHighestOffset =
        Flow[ConsumerRecord[K, V]]
          .scan(allHighestOffsets)(emitRecordRemovingConsumedPartition)
          .takeWhile(_.partitionOffsets.nonEmpty, inclusive = true)
          .collect { case WithRecord(r) => r }

      kafkaSource[K, V](lowestOffsets, config, maybeConsumerSettings)
        .idleTimeout(config.idleTimeout)
        .via(filterBelowHighestOffset)
        .watchTermination() { case (mat, terminationF) =>
          terminationF.onComplete(
            _.fold(
              logger.error(s"Error occurred while loading data from ${offsets.keys.show}", _),
              _ => logger.info(s"Successfully loaded data from ${offsets.keys.show}")
            )
          )(system.dispatcher)
          mat
        }
    }

    import system.dispatcher

    Source.futureSource {
      logOffsets.map(topicDataSource)
    }
  }

  private def kafkaSource[K : Deserializer, V : Deserializer](
      startingOffsets: Map[TopicPartition, Long],
      config: TopicLoaderConfig,
      maybeConsumerSettings: Option[ConsumerSettings[Array[Byte], Array[Byte]]]
  )(implicit system: ActorSystem) =
    Consumer
      .plainSource(consumerSettings(maybeConsumerSettings), Subscriptions.assignmentWithOffset(startingOffsets))
      .buffer(config.bufferSize.value, OverflowStrategy.backpressure)
      .map(cr => cr.bimap(_.deserialize[K](cr.topic), _.deserialize[V](cr.topic)))

  def consumerSettings(
      maybeConsumerSettings: Option[ConsumerSettings[Array[Byte], Array[Byte]]]
  )(implicit system: ActorSystem): ConsumerSettings[Array[Byte], Array[Byte]] =
    maybeConsumerSettings.getOrElse(
      ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    )

  private def withStandaloneConsumer[T](
      settings: ConsumerSettings[Array[Byte], Array[Byte]]
  )(f: Consumer[Array[Byte], Array[Byte]] => T): T = {
    val consumer = settings.createKafkaConsumer()
    try f(consumer)
    finally consumer.close()
  }

  private def offsetsFrom(partitions: List[TopicPartition])(
      f: JList[TopicPartition] => JMap[TopicPartition, JLong]
  ): Map[TopicPartition, Long] =
    f(partitions.asJava).asScala.toMap.map { case (p, o) => p -> o.longValue }

  private def emitRecordRemovingConsumedPartition[K, V](
      t: HighestOffsetsWithRecord[K, V],
      r: ConsumerRecord[K, V]
  ): HighestOffsetsWithRecord[K, V] = {
    val partitionHighest: Option[Long]         = t.partitionOffsets.get(new TopicPartition(r.topic, r.partition))
    val reachedHighest: Option[TopicPartition] = for {
      offset  <- partitionHighest
      highest <- if (r.offset >= offset) new TopicPartition(r.topic, r.partition).some else None
      _        = logger.info(s"Finished loading data from ${r.topic}-${r.partition}")
    } yield highest

    val updatedHighests = reachedHighest.fold(t.partitionOffsets)(highest => t.partitionOffsets - highest)
    val emittableRecord = partitionHighest.collect { case h if r.offset() <= h => r }
    HighestOffsetsWithRecord(updatedHighests, emittableRecord)
  }
}

trait DeprecatedMethods { self: TopicLoader =>

  import TopicLoader._

  @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.2.8")
  def apply[T](
      strategy: LoadTopicStrategy,
      topics: NonEmptyList[String],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T]
  )(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] =
    fromTopics(strategy, topics, onRecord, valueDeserializer)

  /** Consumes the records from the provided topics, passing them through `onRecord`.
    */
  @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
  def fromTopics[T](
      strategy: LoadTopicStrategy,
      topics: NonEmptyList[String],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T]
  )(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {
    val logOffsets = logOffsetsForTopics(topics, strategy)
    deprecatedLoad(logOffsets, onRecord, valueDeserializer)
  }

  private val keySerializer = new StringDeserializer

  /** Consumes the records from the provided partitions, passing them through `onRecord`.
    */
  @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
  def fromPartitions[T](
      strategy: LoadTopicStrategy,
      partitions: NonEmptyList[TopicPartition],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T]
  )(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {
    val logOffsets = logOffsetsForPartitions(partitions, strategy)
    deprecatedLoad(logOffsets, onRecord, valueDeserializer)
  }

  private def deprecatedLoad[T](
      logOffsets: Future[Map[TopicPartition, LogOffsets]],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T]
  )(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {
    val config = Config.loadOrThrow(system.settings.config).topicLoader

    import system.dispatcher

    load(logOffsets, config, None)(keySerializer, valueDeserializer, system)
      .mapMaterializedValue(_ => NotUsed)
      .mapAsync(config.parallelism.value)(r => onRecord(r).map(_ => r))
      .fold(logOffsets) { case (acc, _) => acc }
      .flatMapConcat(Source.future)
      .map(_.map { case (p, o) => p -> o.highest })
  }
}
