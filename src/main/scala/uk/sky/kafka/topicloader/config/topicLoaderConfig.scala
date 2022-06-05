package uk.sky.kafka.topicloader.config

import java.util.concurrent.TimeUnit

import cats.data.{Validated, ValidatedNec}
import cats.implicits.*
import com.typesafe.config.{Config as TypesafeConfig, ConfigException}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

final case class PosInt private (_value: Int) {
  val value: Int = _value
}

object PosInt {
  def apply(value: Int): Either[IllegalArgumentException, PosInt] =
    if (value > 0) new PosInt(value).asRight
    else new IllegalArgumentException(s"$value is not a positive Int").asLeft
}

final case class Config(topicLoader: TopicLoaderConfig)

final case class TopicLoaderConfig(
    idleTimeout: FiniteDuration,
    bufferSize: PosInt,
    clientId: Option[String]
)

object Config {
  private val basePath = "topic-loader"

  def load(config: TypesafeConfig): ValidatedNec[ConfigException, Config] = {
    val idleTimeout = Try(
      FiniteDuration(config.getDuration(s"$basePath.idle-timeout").toNanos, TimeUnit.NANOSECONDS)
    ).validate(s"$basePath.idle-timeout")

    val bufferSize = PosInt(config.getInt(s"$basePath.buffer-size"))
      .validate(s"$basePath.buffer-size")

    val clientId = Try(
      if (config.hasPath(s"$basePath.client-id")) Some(config.getString(s"$basePath.client-id"))
      else None
    ).validate(s"$basePath.client-id")

    (idleTimeout, bufferSize, clientId).mapN(TopicLoaderConfig.apply).map(Config.apply)
  }

  def loadOrThrow(config: TypesafeConfig): Config = load(config) match {
    case Validated.Valid(config) => config
    case Validated.Invalid(e)    =>
      throw new ConfigException.Generic(
        s"Error loading config:\n\t${e.toNonEmptyList.toList.mkString("\t\n")}\n"
      )
  }
}
