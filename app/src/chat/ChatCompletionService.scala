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

// NOTE: see ollama defaults: https://github.com/ollama/ollama/blob/main/api/types.go#L592
final case class ChatCompletionSettings(
  // maxTokens: Int,
  // temperature: Double,
  // topP: Double,
  // frequencyPenalty: Double,
  // presencePenalty: Double,
  // stop: Seq[String],
)
