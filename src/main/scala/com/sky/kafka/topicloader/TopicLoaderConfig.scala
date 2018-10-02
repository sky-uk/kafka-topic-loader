package com.sky.kafka.topicloader

import cats.data.NonEmptyList
import com.sky.kafka.LoadTopicStrategy

import scala.concurrent.duration.FiniteDuration

/**
  * @param strategy
  * All records on a topic can be consumed using the `LoadAll` strategy.
  * All records up to the last committed offset of the configured `group.id` (provided in your application.conf)
  * can be consumed using the `LoadCommitted` strategy.
  *
  * @param parallelism
  * Determines how many Kafka records are processed by [[TopicLoader]].
  * We recommend using a parallelism > 1 if you are processing the records by sending them to an [[akka.actor.Actor]].
  * This is so that messages are buffered in the [[akka.actor.Actor]]'s mailbox, improving performance versus
  * using a parallelism of 1.
  *
  */
final case class TopicLoaderConfig(strategy: LoadTopicStrategy,
                                   topics: NonEmptyList[String],
                                   idleTimeout: FiniteDuration,
                                   parallelism: Int = 1)
