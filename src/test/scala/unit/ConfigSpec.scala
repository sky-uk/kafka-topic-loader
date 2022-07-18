package unit

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import base.UnitSpecBase
import cats.implicits.*
import com.typesafe.config.{ConfigException, ConfigFactory}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import uk.sky.kafka.topicloader.TopicLoader.consumerSettings
import uk.sky.kafka.topicloader.config.Config

import scala.concurrent.duration.*

class ConfigSpec extends UnitSpecBase {

  "consumerSettings" should {

    val system: ActorSystem = ActorSystem(
      "test-actor-system",
      ConfigFactory.parseString(
        s"""
           |topic-loader {
           |  idle-timeout = 1 second
           |  buffer-size = 10
           |}
             """.stripMargin
      )
    )

    "use given ConsumerSettings when given some settings" in {
      val config = Config.loadOrThrow(system.settings.config)

      val testSettings: ConsumerSettings[Array[Byte], Array[Byte]] =
        ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
          .withProperty("test", "testing")

      val result: ConsumerSettings[Array[Byte], Array[Byte]] =
        consumerSettings(testSettings.some, config.topicLoader)(system)

      result.properties("test") shouldBe "testing"
    }

    "use default ConsumerSettings if given None for maybeConsumerSettings" in {
      val config = Config.loadOrThrow(system.settings.config)

      val result: ConsumerSettings[Array[Byte], Array[Byte]] = consumerSettings(None, config.topicLoader)(system)

      result.properties.get("test") shouldBe None
    }

    "use the config client ID over the akka client ID" in {
      implicit val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 1 second
             |  buffer-size = 10
             |  client-id = test-client-id
             |}
             |akka.kafka.consumer.kafka-clients.client.id = akka-client-id
             """.stripMargin
        )
      )

      val config = Config.loadOrThrow(system.settings.config)

      val result: ConsumerSettings[Array[Byte], Array[Byte]] = consumerSettings(None, config.topicLoader)

      result.properties(ConsumerConfig.CLIENT_ID_CONFIG) shouldBe "test-client-id"
    }

    "use the akka client ID if no client ID is specified" in {
      implicit val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 1 second
             |  buffer-size = 10
             |}
             |akka.kafka.consumer.kafka-clients.client.id = akka-client-id
             """.stripMargin
        )
      )

      val config = Config.loadOrThrow(system.settings.config)

      val result: ConsumerSettings[Array[Byte], Array[Byte]] = consumerSettings(None, config.topicLoader)

      result.properties(ConsumerConfig.CLIENT_ID_CONFIG) shouldBe "akka-client-id"
    }
  }

  "config" should {
    "load a valid config correctly" in {

      val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 1 second
             |  buffer-size = 10
             |  client-id = test-client-id
             |}
             """.stripMargin
        )
      )

      val config = Config.loadOrThrow(system.settings.config)
      config.topicLoader.idleTimeout shouldBe 1.second
      config.topicLoader.bufferSize.value shouldBe 10
      config.topicLoader.clientId.value shouldBe "test-client-id"
    }

    "fail to load an invalid config" in {
      val system: ActorSystem = ActorSystem(
        "test-actor-system",
        ConfigFactory.parseString(
          s"""
             |topic-loader {
             |  idle-timeout = 9999999999999999999999 seconds
             |  buffer-size = -1
             |}
             """.stripMargin
        )
      )

      val exception: ConfigException = intercept[ConfigException](Config.loadOrThrow(system.settings.config))

      exception.getMessage should (
        include(
          "Invalid value at 'topic-loader.idle-timeout': Could not parse duration number '9999999999999999999999'"
        ) and include("Invalid value at 'topic-loader.buffer-size': -1 is not a positive Int")
      )
    }
  }
}
