# kafka-topic-loader
Reads the contents of provided Kafka topics, either the topics in their entirety or up until a consumer groups last committed Offset depending on which `LoadTopicStrategy` you provide.

Add the following to your `build.sbt`:
```scala
libraryDependencies += "com.sky" %% "kafka-topic-loader" % "1.2.4"

resolvers += "bintray-sky-uk-oss-maven" at "https://dl.bintray.com/sky-uk/oss-maven"
```

```scala
val config = TopicLoaderConfig(idleTimeout = 2.minutes, bufferSize = 1000, parallelism = 2)

val storeRecords: ConsumerRecord[String, SourceEntity] => Future[BusinessEntity] = {
    /* store records in akka.Actor for example */
}

val stream =
    TopicLoader(config, storeRecords, new LongDeserializer)
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
