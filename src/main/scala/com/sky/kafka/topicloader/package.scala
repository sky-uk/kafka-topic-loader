package com.sky.kafka

import cats.data.Reader

package object topicloader {

  type Configure[T] = Reader[TopicLoaderConfig, T]

}
