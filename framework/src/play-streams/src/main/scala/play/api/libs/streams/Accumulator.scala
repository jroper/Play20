package play.api.libs.streams

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Source, Keep, Flow, Sink }
import org.reactivestreams.{ Publisher, Subscription, Subscriber }

import scala.concurrent.{ Promise, ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
 * An accumulator of elements into a future of a result.
 *
 * This is essentially a lightweight wrapper around a Sink that gets materialised to a Future, but provides convenient
 * methods for working directly with that future as well as transforming the input.
 */
sealed trait Accumulator[-E, +A] {

  protected def mapMaterialized[B](f: Future[A] => Future[B]): Accumulator[E, B]

  /**
   * Map the result of this accumulator to something else.
   */
  def map[B](f: A => B)(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMaterialized(_.map(f))

  /**
   * Map the result of this accumulator to a future of something else.
   */
  def mapFuture[B](f: A => Future[B])(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMaterialized(_.flatMap(f))

  /**
   * Recover from errors encountered by this accumulator.
   */
  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMaterialized(_.recover(pf))

  /**
   * Recover from errors encountered by this accumulator.
   */
  def recoverWith[B >: A](pf: PartialFunction[Throwable, Future[B]])(implicit executor: ExecutionContext): Accumulator[E, B] =
    mapMaterialized(_.recoverWith(pf))

  /**
   * Return a new accumulator that first feeds the input through the given flow before it goes through this accumulator.
   */
  def through[F](flow: Flow[F, E, _]): Accumulator[F, A]

  /**
   * Right associative operator alias for through.
   *
   * This can be used for a more fluent DSL that matches the flow of the data, for example:
   *
   * {{{
   *   val intAccumulator: Accumulator[Int, Unit] = ...
   *   val toInt = Flow[String].map(_.toInt)
   *   val stringAccumulator = toInt ~>: intAccumulator
   * }}}
   */
  def ~>:[F](flow: Flow[F, E, _]): Accumulator[F, A] =
    through(flow)

  /**
   * Run this accumulator by feeding in the given source.
   */
  def run(source: Source[E, _])(implicit materializer: FlowMaterializer): Future[A]

  /**
   * Run this accumulator by feeding a completed source into it.
   */
  def run()(implicit materializer: FlowMaterializer): Future[A]
  /**
   * Right associative operator alias for run.
   *
   * This can be used for a more fluent DSL that matches the flow of the data, for example:
   *
   * {{{
   *   val intAccumulator: Accumulator[Int, Int] = ...
   *   val source = Source(1 to 3)
   *   val intFuture = source ~>: intAccumulator
   * }}}
   */
  def ~>:(source: Source[E, _])(implicit materializer: FlowMaterializer): Future[A] =
    run(source)

  /**
   * Run this accumulator by feeding a single element into it.
   */
  def run(e: E)(implicit materializer: FlowMaterializer): Future[A]

  /**
   * Convert this accumulator to a Sink that gets materialised to a Future.
   */
  def toSink: Sink[E, Future[A]]
}

private class ImmediateAccumulator[-E, +A](many: Sink[E, Future[A]], zeroOrOne: Option[Option[E] => Future[A]]) extends Accumulator[E, A] {

  protected def mapMaterialized[B](f: Future[A] => Future[B]): Accumulator[E, B] =
    new ImmediateAccumulator(
      many.mapMaterializedValue(f),
      zeroOrOne match {
        case Some(zo) => Some(zo.andThen(f))
        case None => None
      }
    )

  /**
   * Return a new accumulator that first feeds the input through the given flow before it goes through this accumulator.
   */
  def through[F](flow: Flow[F, E, _]): Accumulator[F, A] =
    new ImmediateAccumulator(flow.toMat(many)(Keep.right), None)

  /**
   * Run this accumulator by feeding in the given source.
   */
  def run(source: Source[E, _])(implicit materializer: FlowMaterializer): Future[A] = {
    source.toMat(many)(Keep.right).run()
  }

  /**
   * Run this accumulator by feeding a completed source into it.
   */
  def run()(implicit materializer: FlowMaterializer): Future[A] =
    zeroOrOne match {
      case Some(f) => f(None)
      case None => run(Source.empty)
    }

  /**
   * Run this accumulator by feeding a single element into it.
   */
  def run(e: E)(implicit materializer: FlowMaterializer): Future[A] =
    zeroOrOne match {
      case Some(f) => f(Some(e))
      case None => run(Source.single(e))
    }

  /**
   * Convert this accumulator to a Sink that gets materialised to a Future.
   */
  def toSink: Sink[E, Future[A]] = many
}

private class FutureAccumulator[-E, +A](future: Future[Accumulator[E, A]], mat: FlowMaterializer) extends Accumulator[E, A] {
  import play.api.libs.iteratee.Execution.Implicits.trampoline

  protected def mapMaterialized[B](f: (Future[A]) => Future[B]) =
    new FutureAccumulator(future.map(_.mapMaterialized(f)), mat)

  /**
   * Return a new accumulator that first feeds the input through the given flow before it goes through this accumulator.
   */
  def through[F](flow: Flow[F, E, _]) =
    new FutureAccumulator(future.map(_.through(flow)), mat)

  /**
   * Run this accumulator by feeding in the given source.
   */
  def run(source: Source[E, _])(implicit materializer: FlowMaterializer) =
    future.flatMap(_.run(source))

  /**
   * Run this accumulator by feeding a completed source into it.
   */
  def run()(implicit materializer: FlowMaterializer) =
    future.flatMap(_.run())

  /**
   * Run this accumulator by feeding a single element into it.
   */
  def run(e: E)(implicit materializer: FlowMaterializer) =
    future.flatMap(_.run(e))

  /**
   * Convert this accumulator to a Sink that gets materialised to a Future.
   */
  def toSink = {
    implicit val mat = this.mat

    // Ideally, we'd use the following code, except due to akka streams bugs...
    // new Accumulator(Sink.publisher[E].mapMaterializedValue { publisher =>
    //  future.recover {
    //    case error => new Accumulator(Sink.cancelled[E].mapMaterializedValue(_ => Future.failed(error)))
    //  }.flatMap { accumulator =>
    //    Source(publisher).toMat(accumulator.toSink)(Keep.right).run()
    //  }
    //})

    val result = Promise[A]()
    val sink = Sink(new Subscriber[E] {
      @volatile var subscriber: Subscriber[_ >: E] = _

      def onSubscribe(sub: Subscription) = future.onComplete {
        case Success(accumulator) =>
          Source(new Publisher[E]() {
            def subscribe(s: Subscriber[_ >: E]) = {
              subscriber = s
              s.onSubscribe(sub)
            }
          }).runWith(accumulator.toSink.mapMaterializedValue { fA =>
            result.completeWith(fA)
          })
        case Failure(error) =>
          sub.cancel()
          result.failure(error)
      }

      def onError(t: Throwable) = subscriber.onError(t)
      def onComplete() = subscriber.onComplete()
      def onNext(t: E) = subscriber.onNext(t)
    })

    sink.mapMaterializedValue(_ => result.future)
  }
}

object Accumulator {

  /**
   * Create a new accumulator from the given Sink.
   */
  def apply[E, A](sink: Sink[E, Future[A]]): Accumulator[E, A] = new ImmediateAccumulator(sink, None)

  /**
   * Create a done accumulator.
   *
   * The underlying sink will cancel as soon as its onSubscribe method is called, and the materialized value will be
   * an immediately available future of `a`.
   */
  def done[A](a: A): Accumulator[Any, A] =
    new ImmediateAccumulator(
      Sink.cancelled[Any].mapMaterializedValue(_ => Future.successful(a)),
      Some(_ => Future.successful(a))
    )

  /**
   * Create a done accumulator.
   *
   * The underlying sink will cancel as soon as its onSubscribe method is called, and the materialized value will be
   * the passed in future.
   */
  def done[A](a: Future[A]): Accumulator[Any, A] =
    new ImmediateAccumulator(
      Sink.cancelled[Any].mapMaterializedValue(_ => a),
      Some(_ => a)
    )

  /**
   * Flatten a future of an accumulator to an accumulator.
   */
  def flatten[E, A](future: Future[Accumulator[E, A]])(implicit materializer: FlowMaterializer): Accumulator[E, A] =
    new FutureAccumulator(future, materializer)

}
