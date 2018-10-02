# kafka-topic-loader
Loads the state of a kafka topics to populate your application on startup

```scala

    import com.sky.kafka.topicloader.TopicLoader.RunAfterSource     // for #runAfter

    val storeRecords: ConsumerRecord[String, SourceEntity] => Future[AssembledEntity] = ???

    def stream: Stream[Out] =
        fromSource
          .via(assemble)
          .runAfter(TopicLoader(LoadCommitted, topics, storeRecords, deserializer, 2 minutes))
```