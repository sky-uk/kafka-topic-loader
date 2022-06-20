package base

import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

abstract class WordSpecBase extends AnyWordSpec with Matchers with ScalaFutures with OptionValues
