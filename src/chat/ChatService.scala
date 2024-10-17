package supportbot
package chat

import cats.syntax.all.*
import fs2.Stream

trait ChatService[F[_]]:
  def chatCompletion(prompt: Prompt): Stream[F, ChatChunkResponse]

type Example = ("User" | "Assistant", String)

/** A prompt template.
  *
  * Based on
  * https://github.com/anthropics/courses/blob/master/prompt_engineering_interactive_tutorial/Anthropic%201P/09_Complex_Prompts_from_Scratch.ipynb
  */
final case class Prompt(
  taskContext: Option[String] = None,       // system
  toneContext: Option[String] = None,       // system
  taskDescription: Option[String] = None,   // system
  examples: Vector[Example] = Vector.empty, // user & assistant back and forth
  queryContext: Option[String] = None,      // user
  query: String,                            // aka immediateTask, user
  precognition: Option[String] = None,      // user
  outputFormatting: Option[String] = None,  // user
  prefill: Option[String] = None,           // assistant
)

trait ChatChunkResponse:
  /** The content of the response, merged from the sequence of deltas in single response chunk.
    */
  def contentDeltas: String
