package com.sky.kafka.topicloader

import cats.data.NonEmptyList
import org.apache.kafka.common.TopicPartition

sealed trait SourceStrategy                                               extends Product with Serializable
final case class FromTopics(topics: NonEmptyList[String])                 extends SourceStrategy
final case class FromPartitions(partitions: NonEmptyList[TopicPartition]) extends SourceStrategy
