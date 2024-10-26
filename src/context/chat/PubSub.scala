package supportbot
package context
package chat

import cats.effect.*
import cats.syntax.all.*
import fs2.{Chunk as _, *}
import fs2.concurrent.Topic

trait PubSub[F[_]]:
  def publish(message: PubSub.Message): F[Unit]
  def subscribe(topicId: String): Stream[F, PubSub.Message]

object PubSub:
  final case class Message(topicId: String, eventType: ChatEvent, content: String)

  def resource[F[_]: Concurrent]: Resource[F, PubSub[F]] =
    Resource
      .make(Topic[F, Message]())(_.close.void)
      .map: topic =>
        new PubSub[F]:
          def publish(message: Message): F[Unit]        =
            topic.publish1(message).void
          def subscribe(id: String): Stream[F, Message] =
            topic.subscribeUnbounded.filter(_.topicId == id)
