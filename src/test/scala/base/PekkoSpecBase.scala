package base

import org.apache.pekko.testkit.{TestKit, TestKitBase}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Suite}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

abstract class PekkoSpecBase extends TestKitBase with Suite with BeforeAndAfterAll with Matchers {

  implicit lazy val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system, 30.seconds, verifySystemShutdown = true)
  }
}
