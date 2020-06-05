package base

import akka.testkit.{TestKit, TestKitBase}
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class AkkaSpecBase extends TestKitBase with Suite with BeforeAndAfterAll with Matchers {

  implicit lazy val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system, 30.seconds, verifySystemShutdown = true)
  }
}
