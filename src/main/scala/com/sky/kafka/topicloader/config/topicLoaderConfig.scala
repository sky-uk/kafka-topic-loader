package com.sky.kafka.topicloader.config

import com.typesafe.config.{Config => TypesafeConfig}

import scala.concurrent.duration.FiniteDuration

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
    Config(
      TopicLoaderConfig(
        config.getFiniteDuration(s"$basePath.idle-timeout"),
        config.getPosInt(s"$basePath.buffer-size"),
        config.getPosInt(s"$basePath.parallelism")
      )
    )
}
