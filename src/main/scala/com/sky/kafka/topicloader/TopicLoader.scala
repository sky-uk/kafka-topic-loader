package com.sky.kafka.topicloader

import java.lang.{Long => JLong}
import java.util.{List => JList, Map => JMap}

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import cats.data.NonEmptyList
import cats.syntax.option._
import cats.syntax.show._
import cats.{Bifunctor, Show}
import com.typesafe.scalalogging.LazyLogging
import eu.timepit.refined.pureconfig._
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.serialization._
import cats.syntax.bifunctor._
import org.apache.kafka.common.TopicPartition
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.Future

object TopicLoader extends TopicLoader with DeprecatedMethods {
  private[topicloader] case class LogOffsets(lowest: Long, highest: Long)

  private case class HighestOffsetsWithRecord[K, V](partitionOffsets: Map[TopicPartition, Long],
                                                    consumerRecord: Option[ConsumerRecord[K, V]] =
                                                      none[ConsumerRecord[K, V]])

  private implicit class DeserializerOps(val bytes: Array[Byte]) extends AnyVal {
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
        ConsumerRecord.NULL_CHECKSUM.toLong,
        fab.serializedKeySize,
        fab.serializedValueSize,
        f(fab.key),
        g(fab.value),
        fab.headers
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

  /**
    * Source that loads the specified topics from the beginning and completes
    * when the offsets reach the point specified by the requested strategy. Materializes to a Future[Consumer.Control]
    * where the Future represents the retrieval of offsets and the Consumer.Control the Kafka consumer stream.
    */
  def load[K : Deserializer, V : Deserializer](
      topics: NonEmptyList[String],
      strategy: LoadTopicStrategy
  )(implicit system: ActorSystem): Source[ConsumerRecord[K, V], Future[Consumer.Control]] = {
    val config = loadConfigOrThrow[Config](system.settings.config).topicLoader
    load(logOffsetsForTopics(topics, strategy), config)
  }

  /**
    * Source that loads the specified topics from the beginning. When
    * the latest current offests are reached, the materialised value is
    * completed, and the stream continues.
    */
  def loadAndRun[K : Deserializer, V : Deserializer](
      topics: NonEmptyList[String],
  )(implicit system: ActorSystem): Source[ConsumerRecord[K, V], (Future[Done], Future[Consumer.Control])] = ???

  /**
    * Same as [[TopicLoader.loadAndRun]], but with one stream per partition.
    * See [[akka.kafka.scaladsl.Consumer.plainPartitionedSource]] for an
    * explanation of how the outer Source works.
    */
  def partitionedLoadAndRun[K : Deserializer, V : Deserializer](
      topics: NonEmptyList[String],
  )(implicit system: ActorSystem): Source[(TopicPartition, Source[ConsumerRecord[K, V], Future[Done]]),
                                          Future[Consumer.Control]] = ???

  protected def logOffsetsForPartitions(topicPartitions: NonEmptyList[TopicPartition], strategy: LoadTopicStrategy)(
      implicit system: ActorSystem): Future[Map[TopicPartition, LogOffsets]] =
    fetchLogOffsets(_ => topicPartitions.toList, strategy)

  protected def logOffsetsForTopics(topics: NonEmptyList[String], strategy: LoadTopicStrategy)(
      implicit system: ActorSystem): Future[Map[TopicPartition, LogOffsets]] = {
    val partitionsFromTopics: Consumer[Array[Byte], Array[Byte]] => List[TopicPartition] = c =>
      for {
        t <- topics.toList
        p <- c.partitionsFor(t).asScala
      } yield new TopicPartition(t, p.partition)
    fetchLogOffsets(partitionsFromTopics, strategy)
  }

  private def fetchLogOffsets(
      f: Consumer[Array[Byte], Array[Byte]] => List[TopicPartition],
      strategy: LoadTopicStrategy)(implicit system: ActorSystem): Future[Map[TopicPartition, LogOffsets]] = {
    def earliestOffsets(consumer: Consumer[Array[Byte], Array[Byte]],
                        beginningOffsets: Map[TopicPartition, Long]): Map[TopicPartition, Long] =
      beginningOffsets.keys.map(p => p -> Option(consumer.committed(p)).fold(beginningOffsets(p))(_.offset)).toMap

    import system.dispatcher

    Future {
      withStandaloneConsumer(settings) { c =>
        val offsets          = offsetsFrom(f(c)) _
        val beginningOffsets = offsets(c.beginningOffsets)
        val endOffsets = strategy match {
          case LoadAll       => offsets(c.endOffsets)
          case LoadCommitted => earliestOffsets(c, beginningOffsets)
        }

        beginningOffsets.map {
          case (k, v) => k -> LogOffsets(v, endOffsets(k))
        }
      }
    }
  }

  protected def load[K : Deserializer, V : Deserializer](
      logOffsets: Future[Map[TopicPartition, LogOffsets]],
      config: TopicLoaderConfig
  )(implicit system: ActorSystem): Source[ConsumerRecord[K, V], Future[Consumer.Control]] = {

    def topicDataSource(offsets: Map[TopicPartition, LogOffsets]): Source[ConsumerRecord[K, V], Consumer.Control] = {
      offsets.foreach { case (partition, offset) => logger.info(s"${offset.show} for $partition") }

      val nonEmptyOffsets   = offsets.filter { case (_, o) => o.highest > o.lowest }
      val lowestOffsets     = nonEmptyOffsets.mapValues(_.lowest)
      val allHighestOffsets = HighestOffsetsWithRecord[K, V](nonEmptyOffsets.mapValues(_.highest - 1))

      val filterBelowHighestOffset =
        Flow[ConsumerRecord[K, V]]
          .scan(allHighestOffsets)(emitRecordRemovingConsumedPartition)
          .takeWhile(_.partitionOffsets.nonEmpty, inclusive = true)
          .collect { case WithRecord(r) => r }

      Consumer
        .plainSource(settings, Subscriptions.assignmentWithOffset(lowestOffsets))
        .buffer(config.bufferSize.value, OverflowStrategy.backpressure)
        .idleTimeout(config.idleTimeout)
        .map(cr => cr.bimap(_.deserialize[K](cr.topic), _.deserialize[V](cr.topic)))
        .via(filterBelowHighestOffset)
        .watchTermination() {
          case (mat, terminationF) =>
            terminationF.onComplete(
              _.fold(logger.error(s"Error occurred while loading data from ${offsets.keys.show}", _),
                     _ => logger.info(s"Successfully loaded data from ${offsets.keys.show}")))(system.dispatcher)
            mat
        }
    }

    import system.dispatcher

    Source.fromFutureSource {
      logOffsets.map(topicDataSource)
    }
  }

  private def settings(implicit system: ActorSystem) =
    ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  private def withStandaloneConsumer[T](settings: ConsumerSettings[Array[Byte], Array[Byte]])(
      f: Consumer[Array[Byte], Array[Byte]] => T): T = {
    val consumer = settings.createKafkaConsumer()
    try {
      f(consumer)
    } finally {
      consumer.close()
    }
  }

  private def offsetsFrom(partitions: List[TopicPartition])(
      f: JList[TopicPartition] => JMap[TopicPartition, JLong]): Map[TopicPartition, Long] =
    f(partitions.asJava).asScala.toMap.mapValues(_.longValue)

  private def emitRecordRemovingConsumedPartition[K, V](t: HighestOffsetsWithRecord[K, V],
                                                        r: ConsumerRecord[K, V]): HighestOffsetsWithRecord[K, V] = {
    val partitionHighest: Option[Long] = t.partitionOffsets.get(new TopicPartition(r.topic, r.partition))
    val reachedHighest: Option[TopicPartition] = for {
      offset  <- partitionHighest
      highest <- if (r.offset >= offset) new TopicPartition(r.topic, r.partition).some else None
      _       = logger.info(s"Finished loading data from ${r.topic}-${r.partition}")
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
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] =
    fromTopics(strategy, topics, onRecord, valueDeserializer)

  /**
    * Consumes the records from the provided topics, passing them through `onRecord`.
    */
  @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
  def fromTopics[T](
      strategy: LoadTopicStrategy,
      topics: NonEmptyList[String],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {
    val logOffsets = logOffsetsForTopics(topics, strategy)
    deprecatedLoad(logOffsets, onRecord, valueDeserializer)
  }

  private val keySerializer = new StringDeserializer

  /**
    * Consumes the records from the provided partitions, passing them through `onRecord`.
    */
  @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
  def fromPartitions[T](
      strategy: LoadTopicStrategy,
      partitions: NonEmptyList[TopicPartition],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {
    val logOffsets = logOffsetsForPartitions(partitions, strategy)
    deprecatedLoad(logOffsets, onRecord, valueDeserializer)
  }

  private def deprecatedLoad[T](
      logOffsets: Future[Map[TopicPartition, LogOffsets]],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {
    val config = loadConfigOrThrow[Config](system.settings.config).topicLoader

    import system.dispatcher

    load(logOffsets, config)(keySerializer, valueDeserializer, system)
      .mapMaterializedValue(_ => NotUsed)
      .mapAsync(config.parallelism.value)(r => onRecord(r).map(_ => r))
      .fold(logOffsets) { case (acc, _) => acc }
      .flatMapConcat(Source.fromFuture)
      .map(_.mapValues(_.highest))
  }
}
