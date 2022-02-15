package com.sky.kafka.topicloader

import java.util.concurrent.TimeUnit

import com.typesafe.config.{Config => TypesafeConfig, ConfigException}

import scala.concurrent.duration.FiniteDuration

package object config {
  final case class PosInt(value: Int) {
    require(value > 0)
  }

  implicit class RichConfig(private val underlying: TypesafeConfig) extends AnyVal {
    def getFiniteDuration(path: String): FiniteDuration =
      FiniteDuration(underlying.getDuration(path).toNanos, TimeUnit.NANOSECONDS)

    def getPosInt(path: String): PosInt = {
      val configInt = underlying.getInt(path)
      if (configInt > 0) PosInt(configInt)
      else throw new ConfigException.BadValue(path, "Int is not positive")
    }
  }
}
