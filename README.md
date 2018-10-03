# kafka-topic-loader
Loads the state of Kafka topics to populate your application on startup

```scala
libraryDependencies += "com.sky" %% "kafka-topic-loader" % "0.1.0-SNAPSHOT"
```

```scala
import com.sky.kafka.topicloader.TopicLoader.RunAfterSource     // for #runAfter

val topicLoaderConfig = TopicLoaderConfig(LoadAll, topics, 2.minutes, parallelism = 2)
val storeRecords: ConsumerRecord[String, SourceEntity] => Future[AssembledEntity] = {/* store records in akka.Actor */}

def stream: Stream[Out] =
    fromSource
      .via(assemble)
      .runAfter(TopicLoader(topicLoaderConfig, storeRecords, new LongDeserializer))

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