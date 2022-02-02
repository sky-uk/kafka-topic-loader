package com.sky.kafka.topicloader

import cats.data.Validated._
import cats.data._
import cats.implicits._
import com.typesafe.config.{Config => TypesafeConfig}

import scala.compat.java8.DurationConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

final case class PosInt(value: Int)

final case class Config(topicLoader: TopicLoaderConfig)

/** @param parallelism
  *   Determines how many Kafka records are processed in parallel by [[TopicLoader]]. We recommend using a parallelism >
  *   1 if you are processing the records by sending them to an akka.actor.Actor. This is so that messages are buffered
  *   in the akka.actor.Actor's mailbox, improving performance versus using a parallelism of 1.
  */
final case class TopicLoaderConfig(
    idleTimeout: FiniteDuration,
    bufferSize: PosInt,
//    @deprecated("Kept for backward compatibility until clients can adapt", "TopicLoader 1.3.0")
    parallelism: PosInt = PosInt(1)
)

object Config {

  type ValidationResult[A] = ValidatedNec[ConfigParseError, A]

  private def validateConfig(config: TypesafeConfig): ValidationResult[Config] = {
    def validateDuration(path: String): ValidationResult[FiniteDuration] =
      Try(config.getDuration(path).toScala).toEither.leftMap(e => ConfigParseError(e.getMessage)).toValidatedNec

    def validatePosInt(path: String): ValidationResult[PosInt] = {
      val value = config.getInt(path)
      if (value > 0) PosInt(value).validNec
      else ConfigParseError(s"Invalid value at '$path': $value is not a positive integer").invalidNec
    }

    val basePath = "topic-loader"
    (
      validateDuration(s"$basePath.idle-timeout"),
      validatePosInt(s"$basePath.buffer-size"),
      validatePosInt(s"$basePath.parallelism")
    ).mapN(TopicLoaderConfig.apply)
      .map(Config(_))
  }

  def loadOrThrow(config: TypesafeConfig): Config =
    validateConfig(config) match {
      case Valid(validConfig) => validConfig
      case Invalid(e)         => throw ConfigLoadException(e.toNonEmptyList)
    }
}

final case class ConfigParseError(message: String)

final case class ConfigLoadException(parseErrors: NonEmptyList[ConfigParseError]) extends RuntimeException
