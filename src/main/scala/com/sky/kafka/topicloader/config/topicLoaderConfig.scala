package com.sky.kafka.topicloader.config

import cats.data.Validated
import cats.implicits._
import com.typesafe.config.{Config => TypesafeConfig, ConfigException}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

final case class Config(topicLoader: TopicLoaderConfig)

/** @param parallelism
  *   Determines how many Kafka records are processed in parallel by [[com.sky.kafka.topicloader.TopicLoader]]. We
  *   recommend using a parallelism > 1 if you are processing the records by sending them to an akka.actor.Actor. This
  *   is so that messages are buffered in the akka.actor.Actor's mailbox, improving performance versus using a
  *   parallelism of 1.
  */
final case class TopicLoaderConfig(
    idleTimeout: FiniteDuration,
    bufferSize: PosInt,
//    @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
    parallelism: PosInt = PosInt(1)
)

object Config {
  private val basePath = "topic-loader"

  def loadOrThrow(config: TypesafeConfig): Config =
    (
      Try(config.get[FiniteDuration](s"$basePath.idle-timeout")).validate(),
      Try(config.get[PosInt](s"$basePath.buffer-size")).validate(),
      Try(config.get[PosInt](s"$basePath.parallelism")).validate()
    ).mapN(TopicLoaderConfig.apply).map(Config(_)) match {
      case Validated.Valid(config)   => config
      case Validated.Invalid(errors) =>
        throw new ConfigException.Generic(
          s"Error loading config:\n\t${errors.toNonEmptyList.toList.mkString("\t\n")}\n"
        )
    }
}
