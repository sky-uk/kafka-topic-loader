package com.sky.kafka.topicloader

import cats.data.NonEmptyList
import com.sky.kafka.topicloader.TopicLoader.LoadTopicStrategy

import scala.concurrent.duration.FiniteDuration

final case class TopicLoaderConfig(strategy: LoadTopicStrategy,
                                   topics: NonEmptyList[String],
                                   clientId: String,
                                   groupId: String,
                                   idleTimeout: FiniteDuration,
                                   parallelism: Int = 1)
