package supportbot
package chat

import cats.effect.*
import cats.syntax.all.*
import fs2.{Chunk as _, *}
import fs2.concurrent.Topic

private[chat] trait PubSub[F[_]]:
  def publish(message: ChatService.Response): F[Unit]
  def subscribe(queryId: QueryId): Stream[F, ChatService.Response]

private[chat] object PubSub:
  def resource[F[_]: Concurrent]: Resource[F, PubSub[F]] =
    Resource
      .make(Topic[F, ChatService.Response]())(_.close.void)
      .map: topic =>
        new PubSub[F]:
          def publish(message: ChatService.Response): F[Unit]        =
            topic.publish1(message).void
          def subscribe(id: QueryId): Stream[F, ChatService.Response] =
            topic.subscribeUnbounded.filter(_.queryId == id)
