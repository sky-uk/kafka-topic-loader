# kafka-topic-loader

[![Build Status](https://app.travis-ci.com/sky-uk/kafka-topic-loader.svg?branch=master)](https://app.travis-ci.com/sky-uk/kafka-topic-loader)
[![Maven Central](https://img.shields.io/maven-central/v/uk.sky/kafka-topic-loader_2.13?color=orange)](https://mvnrepository.com/artifact/uk.sky/kafka-topic-loader)
[![Sonatype Nexus (Snapshots)](https://img.shields.io/nexus/s/uk.sky/kafka-topic-loader_2.13?label=snapshot&server=https%3A%2F%2Fs01.oss.sonatype.org)](https://s01.oss.sonatype.org/content/repositories/snapshots/uk/sky/kafka-topic-loader_2.13/)

Reads the contents of provided Kafka topics, either the topics in their entirety or up until a consumer groups last committed Offset depending on which `LoadTopicStrategy` you provide.

As of version `1.3.0`, data can be loaded either from complete topics using `load` or `loadAndRun`.

Since version `1.4.0` the library is cross compiled for scala versions `2.12` and `2.13`.

Since version `1.6.0` the library is cross compiled for scala versions `2.12`, `2.13` and `3`.

Add the following to your `build.sbt`:

```scala
libraryDependencies += "uk.sky" %% "kafka-topic-loader" % "<version>"
```

## Examples

### Simple load

```scala
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import cats.data.NonEmptyList
import org.apache.kafka.common.serialization.{Deserializer, StringDeserializer}
import uk.sky.kafka.topicloader.{LoadAll, TopicLoader}

implicit val system: ActorSystem                      = ActorSystem()
implicit val stringDeserializer: Deserializer[String] = new StringDeserializer()

val stream = TopicLoader
  .load[String, String](NonEmptyList.one("topic-to-load"), LoadAll)
  .mapAsync(1)(_ => ??? /* store records in akka.Actor for example */ )
  .runWith(Sink.ignore)
```

### Load and run

`loadAndRun` will load the topics, complete the `Future[Done]` from the materialised value and then carry on
running, emitting any new records that appear on the topics. An example use-case for this is a REST API that holds the
contents of a Kafka topic in memory. This kind of application doesn't need to commit offsets and can use the `Future[Done]` to determine readiness.

```scala
import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Sink
import cats.data.NonEmptyList
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, Deserializer, StringDeserializer}
import uk.sky.kafka.topicloader.TopicLoader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future

object Main extends App {

  implicit val system: ActorSystem = ActorSystem()

  import system.dispatcher

  implicit val keyDeserializer: Deserializer[String]        = new StringDeserializer()
  implicit val valueDeserializer: Deserializer[Array[Byte]] = new ByteArrayDeserializer()

  val state = new SimplifiedState

  val (initialLoadingFuture, controlF): (Future[Done], Future[Consumer.Control]) =
    TopicLoader
      .loadAndRun[String, Array[Byte]](NonEmptyList.one("topic-to-load"))
      .to(Sink.foreach(record => state.store.put(record.key, record.value)))
      .run()

  initialLoadingFuture.foreach(_ => state.isAppReady.set(true))

  class SimplifiedState {

    /** API requests may query this state
      */
    val store = new ConcurrentHashMap[String, Array[Byte]]()

    /** A readiness endpoint could be created that queries this
      */
    val isAppReady = new AtomicBoolean()
  }
}
```

### Partitioned load

Data can also be loaded from specific partitions using `partitionedLoad`. By loading from specific partitions the topic
loader can be used by multiple application instances with separate streams per set of partitions (see [Alpakka kafka](https://doc.akka.io/docs/akka-stream-kafka/current/consumer.html#source-per-partition) and below).

```scala
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import cats.data.NonEmptyList
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{Deserializer, LongDeserializer, StringDeserializer}
import uk.sky.kafka.topicloader.{LoadAll, TopicLoader}

implicit val system: ActorSystem = ActorSystem()

implicit val keyDeserializer: Deserializer[String] = new StringDeserializer()
implicit val longDeserializer: LongDeserializer    = new LongDeserializer()

val consumerSettings: ConsumerSettings[String, Long] = ???

val stream: Source[ConsumerRecord[String, Long], Consumer.Control] =
  Consumer
    .plainPartitionedSource(consumerSettings, Subscriptions.topics("topic-to-load"))
    .flatMapConcat { case (topicPartition, source) =>
      TopicLoader
        .partitionedLoad[String, java.lang.Long](NonEmptyList.one(topicPartition), LoadAll)
        .flatMapConcat(_ => source)
    }
```

## Configuring your consumer group.id

You should configure the `akka.kafka.consumer.kafka-clients.group.id` to match that of your application.

e.g

```
akka.kafka {
  consumer.kafka-clients {
    bootstrap.servers = ${?KAFKA_BROKERS}
    group.id = assembler-consumer-group
  }
  producer.kafka-clients {
    bootstrap.servers = ${?KAFKA_BROKERS}
  }
}
```
