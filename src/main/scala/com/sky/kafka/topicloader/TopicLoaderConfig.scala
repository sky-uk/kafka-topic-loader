package com.sky.kafka.topicloader

import cats.data.NonEmptyList
import com.sky.kafka.LoadTopicStrategy

import scala.concurrent.duration.FiniteDuration

final case class TopicLoaderConfig(strategy: LoadTopicStrategy,
                                   topics: NonEmptyList[String],
                                   idleTimeout: FiniteDuration,
                                   parallelism: Int = 1)
