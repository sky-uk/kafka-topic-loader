package com.sky.kafka.topicloader

import cats.data.Validated.{Invalid, Valid}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import eu.timepit.refined.cats.syntax._
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._

import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration

final case class Config(topicLoader: TopicLoaderConfig)

/** @param parallelism
  *   Determines how many Kafka records are processed in parallel by [[TopicLoader]]. We recommend using a parallelism >
  *   1 if you are processing the records by sending them to an akka.actor.Actor. This is so that messages are buffered
  *   in the akka.actor.Actor's mailbox, improving performance versus using a parallelism of 1.
  */
final case class TopicLoaderConfig(
    idleTimeout: FiniteDuration,
    bufferSize: Int Refined Positive,
    @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
    parallelism: Int Refined Positive = 1
)

object Config {
  def load(): Config = {
    val basePath     = "topic-loader"
    val loadedConfig = ConfigFactory.load()
    (
      Valid(loadedConfig.getDuration(s"$basePath.idle-timeout").toScala),
      refineV[Positive](loadedConfig.getInt(s"$basePath.buffer-size")).toValidatedNec,
      refineV[Positive](loadedConfig.getInt(s"$basePath.parallelism")).toValidatedNec
    )
      .mapN(TopicLoaderConfig.apply) match {
      case Valid(topicLoaderConfig) => Config(topicLoaderConfig)
      case Invalid(e)               => throw ConfigLoadException(e.show)
    }
  }
}

final case class ConfigLoadException(message: String) extends RuntimeException
