package supportbot

import java.util.UUID
import cats.effect.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import unindent.*

opaque type ContextId = UUID
object ContextId:
  inline def apply(uuid: UUID): ContextId = uuid
  def of: IO[ContextId]                   = IO.randomUUID

  given JsonValueCodec[ContextId] = JsonCodecMaker.make

/** A prompt template.
  *
  * Based on
  * https://github.com/anthropics/courses/blob/master/prompt_engineering_interactive_tutorial/Anthropic%201P/09_Complex_Prompts_from_Scratch.ipynb
  */
enum PromptTemplate derives ConfiguredJsonValueCodec:
  case Structured(
    taskContext: Option[String] = None,                      // system
    toneContext: Option[String] = None,                      // system
    taskDescription: Option[String] = None,                  // system
    examples: Vector[PromptTemplate.Example] = Vector.empty, // user & assistant back and forth
    queryTemplate: String,                                   // user template, aka immediateTask, user
    queryContextTemplate: Option[String] = None,             // user template
    precognition: Option[String] = None,                     // user
    outputFormatting: Option[String] = None,                 // user
    prefill: Option[String] = None,                          // assistant
  )

final case class RenderedPrompt(
  system: Option[String],
  examples: Vector[PromptTemplate.Example],
  user: String,
  assistant: Option[String],
)

object PromptTemplate:
  lazy val queryVar   = "{{query}}"
  lazy val contextVar = "{{context}}"

  lazy val default = PromptTemplate.Structured(
    taskContext = i"""
      You are an assistant for question-answering tasks. 
      Use the following pieces of retrieved context to answer the question. 
      If you don't know the answer, just say that you don't know. 
      Use three sentences maximum and keep the answer concise.
      """.some,
    queryTemplate = i"""<query> $queryVar </query>""",
    queryContextTemplate = i"""<context> $contextVar </context>""".some,
  )

  enum Example:
    case User(text: String)
    case Assistant(text: String)

final case class Prompt(
  query: String,
  queryContext: Option[String],
  template: PromptTemplate,
) derives ConfiguredJsonValueCodec:
  import PromptTemplate.*

  def render: RenderedPrompt =
    template match
      case tmpl: PromptTemplate.Structured =>
        RenderedPrompt(
          system = Vector(
            tmpl.taskContext,
            tmpl.toneContext,
            tmpl.taskDescription,
          ).map(_.getOrElse("")).mkString("\n").some,
          examples = tmpl.examples,
          user = Vector(
            (tmpl.queryContextTemplate, queryContext)
              .mapN((tmpl, query) => tmpl.replace(contextVar, query)),
            tmpl.queryTemplate.replace(queryVar, query).some,
            tmpl.precognition,
            tmpl.outputFormatting,
          ).map(_.getOrElse("")).mkString("\n"),
          assistant = tmpl.prefill,
        )

final case class RetrievalSettings(
  topK: Int,
  fragmentLookupRange: RetrievalSettings.LookupRange,
) derives ConfiguredJsonValueCodec

object RetrievalSettings:
  lazy val default = RetrievalSettings(
    topK = 15,
    fragmentLookupRange = LookupRange(lookBack = 5, lookAhead = 5),
  )

  final case class LookupRange(lookBack: Int, lookAhead: Int)

/** Chat completion settings.
  *
  * See ollama defaults: https://github.com/ollama/ollama/blob/main/api/types.go#L592
  *
  * @param logitBias
  *   Modify the likelihood of specified tokens appearing in the completion.
  * @param maxTokens
  *   The maximum number of tokens to generate in the chat completion.
  * @param n
  *   How many chat completion choices to generate for each input message.
  * @param presencePenalty
  *   Number between -2.0 and 2.0. Positive values penalize new tokens based on whether they appear in the text so far,
  *   increasing the model's likelihood to talk about new topics.
  *
  * @param frequencyPenalty
  *   Number between -2.0 and 2.0. Positive values penalize new tokens based on their existing frequency in the text so
  *   far, decreasing the model's likelihood to repeat the same line verbatim.
  * @param temperature
  *   What sampling temperature to use, between 0 and 2. Higher values like 0.8 will make the output more random, while
  *   lower values like 0.2 will make it more focused and deterministic.
  * @param topP
  *   An alternative to sampling with temperature, called nucleus sampling, where the model considers the results of the
  *   tokens with top_p probability mass. So 0.1 means only the tokens comprising the top 10% probability mass are
  *   considered.
  */
final case class ChatCompletionSettings(
  logitBias: Option[Map[String, Float]] = None,
  maxTokens: Option[Int] = None,
  n: Option[Int] = None,
  frequencyPenalty: Option[Double] = None,
  presencePenalty: Option[Double] = None,
  temperature: Option[Double] = None,
  topP: Option[Double] = None,
) derives ConfiguredJsonValueCodec

object ChatCompletionSettings:
  lazy val default: ChatCompletionSettings =
    ChatCompletionSettings()
