package com.sky.kafka.topicloader

import scala.jdk.CollectionConverters._

object CollectionConverters {
  implicit class JIteratorOps[A](private val self: java.util.Iterator[A]) extends AnyVal {
    def asScala: Iterator[A] = IteratorHasAsScala(self).asScala
  }
  implicit class JListOps[A](private val self: java.util.List[A]) extends AnyVal {
    def asScala: List[A] = ListHasAsScala(self).asScala.toList
  }
  implicit class JMapOps[K, V](private val self: java.util.Map[K, V]) extends AnyVal {
    def asScala: Map[K, V] = MapHasAsScala(self).asScala.toMap
  }
  implicit class ListOps[A](private val self: List[A]) extends AnyVal {
    def asJava: java.util.List[A] = SeqHasAsJava(self).asJava
  }
  implicit class SetOps[A](private val self: Set[A]) extends AnyVal {
    def asJava: java.util.Set[A] = SetHasAsJava(self).asJava
  }
}
