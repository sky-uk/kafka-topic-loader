package com.sky.kafka.topicloader

import java.time.Duration
import java.util.concurrent.TimeUnit

import cats.data.ValidatedNec
import cats.implicits._
import com.typesafe.config.{Config => TypesafeConfig, ConfigException}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

package object config {
  type ValidationResult[A] = ValidatedNec[Throwable, A]

  implicit class TryOps[A](t: Try[A]) {
    def validate(): ValidationResult[A] = t.toEither.toValidatedNec
  }

  final case class PosInt(value: Int) {
    require(value > 0)
  }

  trait FromConfig[A] {
    def fromConfig(path: String, config: TypesafeConfig): A
  }

  object FromConfig {
    implicit val intReader: FromConfig[Int]                       = (path: String, config: TypesafeConfig) => config.getInt(path)
    implicit val posIntReader: FromConfig[PosInt]                 = (path: String, config: TypesafeConfig) =>
      Try(PosInt(intReader.fromConfig(path, config))) match {
        case Failure(exception) =>
          exception match {
            case _: IllegalArgumentException => throw new ConfigException.BadValue(path, "Int is not positive")
            case _                           => throw exception
          }
        case Success(value)     => value
      }
    implicit val durationReader: FromConfig[Duration]             = (path: String, config: TypesafeConfig) =>
      config.getDuration(path)
    implicit val finiteDurationReader: FromConfig[FiniteDuration] = (path: String, config: TypesafeConfig) =>
      FiniteDuration(durationReader.fromConfig(path, config).toNanos, TimeUnit.NANOSECONDS)
  }

  implicit class RichConfig(private val underlying: TypesafeConfig) extends AnyVal {

    def get[A : FromConfig](path: String): A = implicitly[FromConfig[A]].fromConfig(path, underlying)

  }
}