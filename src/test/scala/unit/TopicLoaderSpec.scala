package unit
import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.Timeout
import base.{AkkaSpecBase, WordSpecBase}
import com.sky.kafka.topicloader._
import com.typesafe.config.ConfigFactory
import org.scalatest._
import unit.SourceOrderingActor._

import scala.concurrent.duration._

class TopicLoaderSpec extends WordSpecBase {

  implicit val timeout = Timeout(5 seconds)
  implicit val pc      = PatienceConfig(150 millis, 15 millis)

  "runAfter" should {
    "at startup run pre-start before the main source" in new TestContext {
      val actorRef =
        system.actorOf(Props[SourceOrderingActor], "sourceTestActor")

      def assertState(expected: SourceOrder): Assertion =
        whenReady(actorRef ? GetState)(_ shouldBe expected)

      val preStartSrc = Source.fromIterator { () =>
        assertState(Uninitialized)
        actorRef ! SourceA
        List.empty.toIterator
      }

      val mainSrc = Source.fromIterator { () =>
        assertState(SourceA)
        actorRef ! SourceB
        List.empty.toIterator
      }.viaMat(KillSwitches.single)(Keep.right)

      whenReady(mainSrc.runAfter(preStartSrc).runWith(Sink.ignore)) { _ =>
        assertState(SourceB)
      }
    }

    "die if second stream dies" in new TestContext {
      failingSrc
        .runAfter(successfulSrc)
        .runWith(Sink.ignore)
        .failed
        .futureValue shouldBe an[Exception]
    }

    "die if first stream dies" in new TestContext {
      successfulSrc
        .runAfter(failingSrc)
        .runWith(Sink.ignore)
        .failed
        .futureValue shouldBe an[Exception]
    }
  }

  private trait TestContext extends AkkaSpecBase {
    val failingSrc =
      Source.single("boom!").delay(10 millis).map(m => throw new Exception(m))
    val successfulSrc =
      Source.single("hello").viaMat(KillSwitches.single)(Keep.right)

    override implicit lazy val system: ActorSystem =
      ActorSystem(
        name = s"test-actor-system-${UUID.randomUUID().toString}",
        config = ConfigFactory.parseString {
          """
            |akka {
            |  jvm-exit-on-fatal-error = off
            |  # Turn these on to help with debugging test failures
            |  loglevel = off
            |  log-dead-letters = off
            |}
          """.stripMargin
        }
      )
  }
}

class SourceOrderingActor extends Actor {
  import SourceOrderingActor._

  override def receive: Receive = storeOrder(Uninitialized)

  def storeOrder(order: SourceOrder): Receive = {
    case Uninitialized => context become storeOrder(Uninitialized)
    case SourceA       => context become storeOrder(SourceA)
    case SourceB       => context become storeOrder(SourceB)
    case GetState      => sender ! order
  }
}

object SourceOrderingActor {
  sealed trait SourceOrder
  case object Uninitialized extends SourceOrder
  case object SourceA       extends SourceOrder
  case object SourceB       extends SourceOrder
  case object GetState      extends SourceOrder
}
