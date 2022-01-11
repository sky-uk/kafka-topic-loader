package com.sky.kafka.topicloader

sealed trait LoadTopicStrategy

/** All records up to the latest offsets at the time when the stream is materialised. Useful when not committing
  * offsets.
  */
case object LoadAll extends LoadTopicStrategy

/** All records up to the last committed offsets of the configured `group.id` (provided in your `application.conf`), or
  * the beginning if there are no offsets.
  */
case object LoadCommitted extends LoadTopicStrategy
