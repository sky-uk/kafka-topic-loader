package com.sky.kafka.topicloader

import scala.collection.JavaConverters._

object CollectionConverters {
  implicit class JIteratorOps[A](private val self: java.util.Iterator[A]) extends AnyVal {
    def asScala: Iterator[A] = asScalaIterator(self)
  }
  implicit class JListOps[A](private val self: java.util.List[A]) extends AnyVal {
    def asScala: List[A] = asScalaBuffer(self).toList
  }
  implicit class JMapOps[K, V](private val self: java.util.Map[K, V]) extends AnyVal {
    def asScala: Map[K, V] = mapAsScalaMap(self).toMap
  }
  implicit class ListOps[A](private val self: List[A]) extends AnyVal {
    def asJava: java.util.List[A] = seqAsJavaList(self)
  }
  implicit class SetOps[A](private val self: Set[A]) extends AnyVal {
    def asJava: java.util.Set[A] = setAsJavaSet(self)
  }
}
