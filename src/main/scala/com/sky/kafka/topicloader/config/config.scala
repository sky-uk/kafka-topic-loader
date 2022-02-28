package com.sky.kafka.topicloader

import cats.data.ValidatedNec
import cats.implicits._
import com.typesafe.config.ConfigException

import scala.util.Try
import scala.util.control.NonFatal

package object config {
  type ValidationResult[A] = ValidatedNec[ConfigException, A]

  implicit class TryOps[A](t: Try[A]) {
    def validate(path: String): ValidationResult[A] = t.toEither.validate(path)
  }

  implicit class EitherOps[A](e: Either[Throwable, A]) {
    def validate(path: String): ValidationResult[A] = e.leftMap {
      case ce: ConfigException => ce
      case NonFatal(e)         => new ConfigException.BadValue(path, e.getMessage)
      case e: Throwable        => throw e
    }.toValidatedNec
  }
}
