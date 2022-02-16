package com.sky.kafka.topicloader

import java.util.concurrent.TimeUnit

import com.typesafe.config.{Config => TypesafeConfig, ConfigException}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

package object config {
  final case class PosInt(value: Int) {
    require(value > 0)
  }

  implicit class RichConfig(private val underlying: TypesafeConfig) extends AnyVal {
    def getFiniteDuration(path: String): FiniteDuration =
      FiniteDuration(underlying.getDuration(path).toNanos, TimeUnit.NANOSECONDS)

    def getPosInt(path: String): PosInt =
      Try(PosInt(underlying.getInt(path))) match {
        case Failure(exception) =>
          exception match {
            case _: IllegalArgumentException => throw new ConfigException.BadValue(path, "Int is not positive")
            case _                           => throw exception
          }
        case Success(value)     => value
      }
  }
}
