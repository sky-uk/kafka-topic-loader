package base

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, WordSpec}

abstract class WordSpecBase extends WordSpec with Matchers with ScalaFutures
