package base

import akka.stream.ActorMaterializer
import akka.testkit.{TestKit, TestKitBase}
import org.scalatest.{BeforeAndAfterAll, Matchers, Suite}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class AkkaSpecBase extends TestKitBase with Suite with BeforeAndAfterAll with Matchers {

  implicit lazy val ec: ExecutionContext   = system.dispatcher
  implicit lazy val mat: ActorMaterializer = ActorMaterializer()

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system, 30.seconds, verifySystemShutdown = true)
  }
}
