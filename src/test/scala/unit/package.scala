import akka.NotUsed
import akka.stream.{KillSwitch, KillSwitches}
import akka.stream.scaladsl.{Keep, Source}

package object unit {

  implicit class PrependStream[T1, M1](val s1: Source[T1, M1]) extends AnyVal {
    def runAfter[T2, M2](s2: Source[T2, M2]): Source[T1, KillSwitch] =
      s1.map(Option.apply)
        .concatLazy(s2.map(_ => None))
        .collect { case Some(t) => t }
        .viaMat(KillSwitches.single)(Keep.right)

    def concatLazy[M2](s2: => Source[T1, M2]): Source[T1, NotUsed] =
      Source(List(() => s2, () => s1)).flatMapConcat(_.apply)
  }

}
