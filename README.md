# kafka-topic-loader
Reads the contents of provided Kafka topics, either the topics in their entirety or up until a consumer groups last committed Offset depending on which `LoadTopicStrategy` you provide.

As of version `1.3.0`, data can be loaded either from complete topics or selected partitions using `TopicLoader.fromTopics` and `TopicLoader.fromPartitions` respectively. By loading from specific partitions the topic loader can be used by multiple application instances with separate streams per set of partitions (see [Alpakka kafka](https://doc.akka.io/docs/akka-stream-kafka/current/consumer.html#source-per-partition) and below).

Add the following to your `build.sbt`:
```scala
libraryDependencies += "com.sky" %% "kafka-topic-loader" % "1.3.0"

resolvers += "bintray-sky-uk-oss-maven" at "https://dl.bintray.com/sky-uk/oss-maven"
```

```scala
import com.sky.kafka.topicloader.{LoadAll, TopicLoader}

val storeRecords: ConsumerRecord[String, SourceEntity] => Future[BusinessEntity] = {
    /* store records in akka.Actor for example */
}

val stream =
    TopicLoader.fromTopics(LoadAll, NonEmptyList.one("topic-to-load"), storeRecords, new LongDeserializer)
      .runWith(Sink.ignore)
```

## Configuring your consumer group.id

You should configure the `akka.kafka.consumer.kafka-clients.group.id` to match that of your application.
This is especially important for the `LoadCommitted` version of `LoadTopicStrategy` to correctly
read up to the correct offset.

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

## Source per partition
```scala
implicit val as: ActorSystem = ActorSystem()

val consumerSettings: ConsumerSettings[String, Long]              = ???
val doBusinessLogic: ConsumerRecord[String, Long] => Future[Unit] = ???

val stream: Source[ConsumerMessage.CommittableMessage[String, Long], Consumer.Control] = Consumer
.committablePartitionedSource(consumerSettings, Subscriptions.topics("topic-to-load"))
.flatMapConcat {
  case (topicPartition, source) =>
    TopicLoader
      .fromPartitions(LoadAll, NonEmptyList.one(topicPartition), doBusinessLogic, new LongDeserializer)
      .flatMapConcat(_ => source)
}
```