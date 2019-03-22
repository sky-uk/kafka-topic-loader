package com.sky.kafka.topicloader

import java.lang.{Long => JLong}
import java.util.{List => JList, Map => JMap}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.{KillSwitch, KillSwitches, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Source}
import cats.data.NonEmptyList
import cats.instances.string.catsStdShowForString
import cats.syntax.option._
import cats.syntax.show._
import cats.{Always, Show}
import com.typesafe.scalalogging.LazyLogging
import eu.timepit.refined.pureconfig._
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, Deserializer, StringDeserializer}
import org.apache.kafka.common.{PartitionInfo, TopicPartition}
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object TopicLoader extends LazyLogging {

  /**
    * Consumes the records from the provided topics, passing them through `onRecord`.
    *
    * @param strategy
    * All records on a topic can be consumed using the `LoadAll` strategy.
    * All records up to the last committed offset of the configured `group.id` (provided in your application.conf)
    * can be consumed using the `LoadCommitted` strategy.
    *
    */
  def apply[T](
      strategy: LoadTopicStrategy,
      topics: NonEmptyList[String],
      onRecord: ConsumerRecord[String, T] => Future[_],
      valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[Map[TopicPartition, Long], NotUsed] = {

    import system.dispatcher

    val config = loadConfigOrThrow[Config](system.settings.config).topicLoader

    val settings =
      ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    def earliestOffsets(topic: String,
                        consumer: Consumer[String, Array[Byte]],
                        beginningOffsets: Map[TopicPartition, Long]): Map[TopicPartition, Long] =
      consumer
        .partitionsFor(topic)
        .asScala
        .map { i =>
          val p = new TopicPartition(topic, i.partition)
          p -> Option(consumer.committed(p)).fold(beginningOffsets(p))(_.offset)
        }
        .toMap

    def topicDataSource(offsets: Map[TopicPartition, LogOffsets]): Source[Map[TopicPartition, LogOffsets], _] = {
      offsets.foreach { case (partition, offset) => logger.info(s"${offset.show} for $partition") }

      val nonEmptyOffsets = offsets.filter { case (_, o) => o.highest > o.lowest }
      val lowestOffsets   = nonEmptyOffsets.mapValues(_.lowest)
      val allHighestOffsets: HighestOffsetsWithRecord[T] =
        HighestOffsetsWithRecord[T](nonEmptyOffsets.mapValues(_.highest - 1))

      val filterBelowHighestOffset =
        Flow[ConsumerRecord[String, T]]
          .scan(allHighestOffsets)(emitRecordRemovingConsumedPartition)
          .takeWhile(_.partitionOffsets.nonEmpty, inclusive = true)
          .collect { case WithRecord(r) => r }

      def handleRecord(r: ConsumerRecord[String, T]) = onRecord(r).map(_ => r)

      nonEmptyOffsets.size match {
        case 0 =>
          logger.info(s"No data to load from ${topics.show}, will return current offsets")
          Source.single(offsets)
        case _ =>
          import SourceOps._
          Source.single(offsets).runAfter {
            Consumer
              .plainSource(settings, Subscriptions.assignmentWithOffset(lowestOffsets))
              .buffer(config.bufferSize.value, OverflowStrategy.backpressure)
              .idleTimeout(config.idleTimeout)
              .map(deserializeValue(_, valueDeserializer))
              .via(filterBelowHighestOffset)
              .mapAsync(config.parallelism.value)(handleRecord)
              .map(_ => offsets)
              .mapMaterializedValue(logResult(_, topics))
          }
      }
    }

    val offsetsSource: Source[Map[TopicPartition, LogOffsets], NotUsed] =
      lazySource {
        withStandaloneConsumer(settings) { c =>
          topics.map { topic =>
            val offsets =
              getOffsets(new TopicPartition(topic, _: Int), c.partitionsFor(topic)) _
            val beginningOffsets = offsets(c.beginningOffsets)
            val partitionToLong = strategy match {
              case LoadAll       => offsets(c.endOffsets)
              case LoadCommitted => earliestOffsets(topic, c, beginningOffsets)
            }
            val endOffsets = partitionToLong
            beginningOffsets.map {
              case (k, v) => k -> LogOffsets(v, endOffsets(k))
            }
          }.toList
            .reduce(_ ++ _)
        }
      }

    offsetsSource.flatMapConcat(topicDataSource).map(_.mapValues(_.highest))
  }

  private def deserializeValue[T](cr: ConsumerRecord[String, Array[Byte]],
                                  valueDeserializer: Deserializer[T]): ConsumerRecord[String, T] =
    new ConsumerRecord[String, T](
      cr.topic,
      cr.partition,
      cr.offset,
      cr.timestamp,
      cr.timestampType,
      ConsumerRecord.NULL_CHECKSUM,
      cr.serializedKeySize,
      cr.serializedValueSize,
      cr.key,
      valueDeserializer.deserialize(cr.topic, cr.value),
      cr.headers
    )

  private def emitRecordRemovingConsumedPartition[T](t: HighestOffsetsWithRecord[T],
                                                     r: ConsumerRecord[String, T]): HighestOffsetsWithRecord[T] = {
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

  private def logResult(control: Consumer.Control, topics: NonEmptyList[String])(implicit ec: ExecutionContext) = {
    control.isShutdown.onComplete {
      case Success(_) => logger.info(s"Successfully loaded data from ${topics.show}")
      case Failure(t) => logger.error(s"Error occurred while loading data from ${topics.show}", t)
    }
    control
  }

  private def lazySource[T](t: => T): Source[T, NotUsed] =
    Source.single(Always(t)).map(_.value)

  private def withStandaloneConsumer[T, U](settings: ConsumerSettings[String, T])(f: Consumer[String, T] => U): U = {
    val consumer = settings.createKafkaConsumer()
    try {
      f(consumer)
    } finally {
      consumer.close()
    }
  }

  private def getOffsets(topicPartition: Int => TopicPartition, partitions: JList[PartitionInfo])(
      f: JList[TopicPartition] => JMap[TopicPartition, JLong]): Map[TopicPartition, Long] =
    f(partitions.asScala.map(p => topicPartition(p.partition)).asJava).asScala.toMap
      .mapValues(_.longValue)

  private case class LogOffsets(lowest: Long, highest: Long)

  private case class HighestOffsetsWithRecord[T](partitionOffsets: Map[TopicPartition, Long],
                                                 consumerRecord: Option[ConsumerRecord[String, T]] =
                                                   none[ConsumerRecord[String, T]])

  private object WithRecord {
    def unapply[T](h: HighestOffsetsWithRecord[T]): Option[ConsumerRecord[String, T]] = h.consumerRecord
  }

  private implicit val showLogOffsets: Show[LogOffsets] = o =>
    s"LogOffsets(lowest = ${o.lowest}, highest = ${o.highest})"
}

object SourceOps {

  implicit class RunAfterSource[T1, M1](val s2: Source[T1, M1]) extends AnyVal {

    /**
      * Prepend s1 Source to s2 Source, returning the source that will run them in order, after the s1 Source
      * ends its result is discarded. Source s1 won't be materialized until s2 completes.
      */
    def runAfter[T2, M2](s1: Source[T2, M2]): Source[T1, KillSwitch] =
      s2.map(EventAction(_))
        .prependLazily(s1.map(_ => Ignore))
        .collect { case Emit(t) => t }
        .viaMat(KillSwitches.single)(Keep.right)

    def prependLazily[M2](s1: => Source[T1, M2]): Source[T1, NotUsed] =
      Source(List(() => s1, () => s2)).flatMapConcat(_.apply)
  }

  private sealed trait EventAction[+T]      extends Product with Serializable
  private case object Ignore                extends EventAction[Nothing]
  private final case class Emit[T](elem: T) extends EventAction[T]
  private object EventAction {
    def apply[T](value: T): EventAction[T] = Emit(value)
  }
}
