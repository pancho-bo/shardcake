package com.devsisters.shardcake.internal

import zio.stream.{ Take, ZStream }
import zio.{ Cause, Promise, Queue, Task, UIO }

private[shardcake] sealed trait ReplyChannel[-A] { self =>
  val await: UIO[Unit]
  val end: UIO[Unit]
  def fail(cause: Cause[Throwable]): UIO[Unit]
  def replySingle(a: A): UIO[Unit]
  def replyStream(stream: ZStream[Any, Throwable, A]): UIO[Unit]
}

private[shardcake] object ReplyChannel {
  case class FromQueue[A](queue: Queue[Take[Throwable, A]]) extends ReplyChannel[A] {
    val await: UIO[Unit]                                           = queue.awaitShutdown
    val end: UIO[Unit]                                             = queue.offer(Take.end).exit.unit
    def fail(cause: Cause[Throwable]): UIO[Unit]                   = queue.offer(Take.failCause(cause)).exit.unit
    def replySingle(a: A): UIO[Unit]                               = queue.offer(Take.single(a)).exit *> end
    def replyStream(stream: ZStream[Any, Throwable, A]): UIO[Unit] =
      (stream
        .runForeachChunk(chunk => queue.offer(Take.chunk(chunk)))
        .onExit(e => queue.offer(e.foldExit(Take.failCause, _ => Take.end)))
        .ignore race await).fork.unit
    val output: ZStream[Any, Throwable, A]                         = ZStream.fromQueueWithShutdown(queue).flattenTake.onError(fail)
  }

  case class FromPromise[A](promise: Promise[Throwable, Option[A]]) extends ReplyChannel[A] {
    val await: UIO[Unit]                                           = promise.await.exit.unit
    val end: UIO[Unit]                                             = promise.succeed(None).unit
    def fail(cause: Cause[Throwable]): UIO[Unit]                   = promise.failCause(cause).unit
    def replySingle(a: A): UIO[Unit]                               = promise.succeed(Some(a)).unit
    def replyStream(stream: ZStream[Any, Throwable, A]): UIO[Unit] =
      stream.runHead
        .flatMap(promise.succeed(_).unit)
        .catchAllCause[Any, Nothing, Unit](fail)
        .fork
        .unit

    val output: Task[Option[A]] = promise.await.onError(fail)
  }

  def single[A]: UIO[FromPromise[A]] =
    Promise.make[Throwable, Option[A]].map(FromPromise(_))

  def stream[A]: UIO[FromQueue[A]] =
    Queue.unbounded[Take[Throwable, A]].map(FromQueue(_))
}
