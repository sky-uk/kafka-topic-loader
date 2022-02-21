package com.sky.kafka.topicloader

import cats.data.ValidatedNec
import cats.implicits._
import com.typesafe.config.{Config => TypesafeConfig}

import scala.util.Try

package object config {
  type ValidationResult[A] = ValidatedNec[Throwable, A]

  implicit class TryOps[A](t: Try[A]) {
    def validate(): ValidationResult[A] = t.toEither.toValidatedNec
  }

  implicit class RichConfig(private val underlying: TypesafeConfig) extends AnyVal {
    def get[A : FromConfig](path: String): A = implicitly[FromConfig[A]].fromConfig(path, underlying)
  }
}
