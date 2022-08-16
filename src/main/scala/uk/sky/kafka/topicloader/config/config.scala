package uk.sky.kafka.topicloader

import cats.data.ValidatedNec
import cats.implicits.*
import com.typesafe.config.ConfigException

import scala.util.Try

package object config {
  type ValidationResult[A] = ValidatedNec[ConfigException, A]

  implicit class TryOps[A](t: Try[A]) {
    private[topicloader] def validate(path: String): ValidationResult[A] = t.toEither.leftMap {
      case ce: ConfigException => ce
      case e: Throwable        => new ConfigException.BadValue(path, e.getMessage)
    }.toValidatedNec
  }

  implicit class EitherOps[A](e: Either[IllegalArgumentException, A]) {
    private[topicloader] def validate(path: String): ValidationResult[A] =
      e.leftMap(e => new ConfigException.BadValue(path, e.getMessage)).toValidatedNec
  }
}
