package com.sky.kafka.topicloader.config

import java.util.concurrent.TimeUnit

import cats.data.{Validated, ValidatedNec}
import cats.implicits._
import com.typesafe.config.{Config => TypesafeConfig, ConfigException}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

final case class PosInt private (_value: Int) {
  val value: Int = _value
}

object PosInt {
  def apply(value: Int): Either[IllegalArgumentException, PosInt] =
    if (value > 0) new PosInt(value).asRight
    else new IllegalArgumentException(s"$value is not a positive Int").asLeft

  val One = new PosInt(1)
}

final case class Config(topicLoader: TopicLoaderConfig)

/** @param parallelism
  *   Determines how many Kafka records are processed in parallel by [[com.sky.kafka.topicloader.TopicLoader]]. We
  *   recommend using a parallelism > 1 if you are processing the records by sending them to an akka.actor.Actor. This
  *   is so that messages are buffered in the akka.actor.Actor's mailbox, improving performance versus using a
  *   parallelism of 1.
  */
final case class TopicLoaderConfig(
    idleTimeout: FiniteDuration,
    bufferSize: PosInt,
//    @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
    parallelism: PosInt = PosInt.One
)

object Config {
  private val basePath = "topic-loader"

  def load(config: TypesafeConfig): ValidatedNec[ConfigException, Config] = {
    val idleTimeout = Try(
      FiniteDuration(config.getDuration(s"$basePath.idle-timeout").toNanos, TimeUnit.NANOSECONDS)
    ).validate(s"$basePath.idle-timeout")

    val bufferSize = PosInt(config.getInt(s"$basePath.buffer-size"))
      .validate(s"$basePath.buffer-size")

    val parallelism = PosInt(config.getInt(s"$basePath.parallelism"))
      .validate(s"$basePath.parallelism")

    (idleTimeout, bufferSize, parallelism).mapN(TopicLoaderConfig.apply).map(Config.apply)
  }

  def loadOrThrow(config: TypesafeConfig): Config = load(config) match {
    case Validated.Valid(config) => config
    case Validated.Invalid(e)    =>
      throw new ConfigException.Generic(
        s"Error loading config:\n\t${e.toNonEmptyList.toList.mkString("\t\n")}\n"
      )
  }
}
