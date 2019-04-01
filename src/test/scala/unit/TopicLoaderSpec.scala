package unit

import akka.actor.ActorSystem
import base.WordSpecBase
import cats.data.NonEmptyList
import com.sky.kafka.topicloader.{FromTopics, LoadAll, TopicLoader}
import com.typesafe.config.ConfigFactory
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.Future

class TopicLoaderSpec extends WordSpecBase {
  "TopicLoader" should {
    "throw IllegalArgumentException when config is invalid" in {
      val invalidConfig = ConfigFactory.parseString(
        """
          |topic-loader.invalid=boom
        """.stripMargin
      )

      assertThrows[IllegalArgumentException](
        TopicLoader[String](LoadAll, FromTopics(NonEmptyList.one("")), Future.successful, new StringDeserializer)(
          ActorSystem("", invalidConfig)))
    }
  }
}
