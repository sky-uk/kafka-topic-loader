package com.sky.kafka.topicloader

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive

import scala.concurrent.duration.FiniteDuration

final case class Config(topicLoader: TopicLoaderConfig)

/**
  * @param parallelism
  * Determines how many Kafka records are processed in parallel by [[TopicLoader]].
  * We recommend using a parallelism > 1 if you are processing the records by sending them to an akka.actor.Actor.
  * This is so that messages are buffered in the akka.actor.Actor's mailbox, improving performance versus
  * using a parallelism of 1.
  *
  */
final case class TopicLoaderConfig(
    idleTimeout: FiniteDuration,
    bufferSize: Int Refined Positive,
    @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.2.8")
    parallelism: Int Refined Positive = 1)
