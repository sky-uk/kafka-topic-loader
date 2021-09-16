package com.sky.kafka.topicloader

import scala.concurrent.duration.FiniteDuration
import pureconfig.ConfigReader

final case class Config(topicLoader: TopicLoaderConfig)
object Config {
  implicit val reader: ConfigReader[Config] = ConfigReader.forProduct1("topic-loader")(Config.apply)
}

/** @param parallelism
  *   Determines how many Kafka records are processed in parallel by [[TopicLoader]]. We recommend using a parallelism >
  *   1 if you are processing the records by sending them to an akka.actor.Actor. This is so that messages are buffered
  *   in the akka.actor.Actor's mailbox, improving performance versus using a parallelism of 1.
  */
final case class TopicLoaderConfig(
    idleTimeout: FiniteDuration,
    bufferSize: Int,
//    @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
    parallelism: Int
)

object TopicLoaderConfig {
  implicit val reader: ConfigReader[TopicLoaderConfig] =
    ConfigReader.forProduct3("idle-timeout", "buffer-size", "parallelism")(TopicLoaderConfig.apply)
}
