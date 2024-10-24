package supportbot
package chat

import supportbot.context.*

import cats.syntax.all.*
import fs2.Stream

trait ChatService[F[_]]:
  def chatCompletion(prompt: Prompt): Stream[F, ChatChunkResponse]

trait ChatChunkResponse:
  /** The content of the response, merged from the sequence of deltas in single response chunk.
    */
  def contentDeltas: String
