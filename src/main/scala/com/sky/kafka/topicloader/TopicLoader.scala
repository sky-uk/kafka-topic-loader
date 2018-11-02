package com.sky.kafka.topicloader

import java.lang.{Long => JLong}
import java.util.{List => JList, Map => JMap}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import cats.data.NonEmptyList
import cats.instances.string.catsStdShowForString
import cats.syntax.option._
import cats.syntax.show._
import cats.{Always, Show}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import org.apache.kafka.common.{PartitionInfo, TopicPartition}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import pureconfig._
import eu.timepit.refined.pureconfig._

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
  def apply[T](strategy: LoadTopicStrategy,
               topics: NonEmptyList[String],
               onRecord: ConsumerRecord[String, T] => Future[_],
               valueDeserializer: Deserializer[T])(implicit system: ActorSystem): Source[_, _] = {

    import system.dispatcher

    def earliestOffsets(topic: String,
                        consumer: Consumer[String, T],
                        beginningOffsets: Map[TopicPartition, Long]): Map[TopicPartition, Long] =
      consumer
        .partitionsFor(topic)
        .asScala
        .map { i =>
          val p = new TopicPartition(topic, i.partition)
          p -> Option(consumer.committed(p)).fold(beginningOffsets(p))(_.offset)
        }
        .toMap

    val config = loadConfigOrThrow[Config](system.settings.config).topicLoader

    val settings =
      ConsumerSettings(system, new StringDeserializer, valueDeserializer)
        .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

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

    def topicDataSource(offsets: Map[TopicPartition, LogOffsets]): Source[ConsumerRecord[String, T], _] = {
      offsets.foreach { case (partition, offset) => logger.info(s"${offset.show} for $partition") }

      val nonEmptyOffsets   = offsets.filter { case (_, o) => o.highest > o.lowest }
      val lowestOffsets     = nonEmptyOffsets.mapValues(_.lowest)
      val allHighestOffsets = HighestOffsetsWithRecord[T](nonEmptyOffsets.mapValues(_.highest - 1))

      val filterBelowHighestOffset =
        Flow[ConsumerRecord[String, T]]
          .scan(allHighestOffsets)(emitRecordRemovingConsumedPartition)
          .takeWhile(_.partitionOffsets.nonEmpty, inclusive = true)
          .collect { case WithRecord(r) => r }

      def handleRecord(r: ConsumerRecord[String, T]) = onRecord(r).map(_ => r)

      nonEmptyOffsets.size match {
        case 0 =>
          logger.info(s"No data to load from ${topics.show}")
          Source.empty
        case _ =>
          Consumer
            .plainSource(settings, Subscriptions.assignmentWithOffset(lowestOffsets))
            .buffer(config.bufferSize.value, OverflowStrategy.backpressure)
            .idleTimeout(config.idleTimeout)
            .via(filterBelowHighestOffset)
            .mapAsync(config.parallelism.value)(handleRecord)
            .mapMaterializedValue(logResult(_, topics))
      }
    }

    offsetsSource.flatMapConcat(topicDataSource)
  }

  private def emitRecordRemovingConsumedPartition[T](t: HighestOffsetsWithRecord[T],
                                                     r: ConsumerRecord[String, T]): HighestOffsetsWithRecord[T] = {
    val partitionHighest: Option[Long] = t.partitionOffsets.get(new TopicPartition(r.topic, r.partition))
    val reachedHighest: Option[TopicPartition] = for {
      offset <- partitionHighest
      highest <- if (r.offset >= offset) {
                  new TopicPartition(r.topic, r.partition)
                }.some
                else None
      _ = logger.info(s"Finished loading data from ${r.topic}-${r.partition}")
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
