package com.sky.kafka

import akka.NotUsed
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{KillSwitch, KillSwitches}

package object topicloader {

  implicit class RunAfterSource[T1, M1](val s2: Source[T1, M1]) extends AnyVal {

    /**
      * Prepend s1 source to s2 source returning the source that will run them in order, after which the s1 Source
      * ends and its result is discarded.
      */
    def runAfter[T2, M2](s1: Source[T2, M2]): Source[T1, KillSwitch] =
      s2.map(emit(_))
        .prependLazily(s1.map(_ => Ignore))
        .collect { case Emit(t) => t }
        .viaMat(KillSwitches.single)(Keep.right)

    def prependLazily[M2](s1: => Source[T1, M2]): Source[T1, NotUsed] =
      Source(List(() => s1, () => s2)).flatMapConcat(_.apply)
  }

  private sealed trait EventAction[+T]
  private case object Ignore                extends EventAction[Nothing]
  private final case class Emit[T](elem: T) extends EventAction[T]
  private def emit[T](t: T): EventAction[T] = Emit(t)
}
