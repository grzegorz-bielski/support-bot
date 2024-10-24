package supportbot
package context

import java.util.UUID
import cats.effect.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

opaque type ContextId = UUID
object ContextId:
  inline def apply(uuid: UUID): ContextId = uuid
  def of(uuid: UUID): IO[ContextId] = IO.randomUUID

  given JsonValueCodec[ContextId] = JsonCodecMaker.make

enum Example:
  case User(text: String)
  case Assistant(text: String)

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
) derives ConfiguredJsonValueCodec
