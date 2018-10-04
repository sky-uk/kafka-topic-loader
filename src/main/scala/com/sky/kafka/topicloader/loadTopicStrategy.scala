package com.sky.kafka.topicloader

sealed trait LoadTopicStrategy
case object LoadAll       extends LoadTopicStrategy
case object LoadCommitted extends LoadTopicStrategy
