package supportbot
package chat

import cats.syntax.all.*
import fs2.Stream

trait ChatCompletionService[F[_]]:
  def chatCompletion(prompt: Prompt, model: Model): Stream[F, ChatChunkResponse]

trait ChatChunkResponse:
  /** The content of the response, merged from the sequence of deltas in single response chunk.
    */
  def contentDeltas: String

