package com.sky.kafka.topicloader

sealed trait LoadTopicStrategy
final case object LoadAll extends LoadTopicStrategy
final case object LoadCommitted extends LoadTopicStrategy
