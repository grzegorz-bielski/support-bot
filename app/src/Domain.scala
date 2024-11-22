package supportbot

import java.util.UUID
import cats.effect.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import unindent.*

/** A prompt template.
  *
  * Based on
  * https://github.com/anthropics/courses/blob/master/prompt_engineering_interactive_tutorial/Anthropic%201P/09_Complex_Prompts_from_Scratch.ipynb
  */
enum PromptTemplate derives ConfiguredJsonValueCodec:
  case Structured(
    taskContext: Option[String] = None,          // system
    toneContext: Option[String] = None,          // system
    taskDescription: Option[String] = None,      // system
    examples: Vector[PromptTemplate.Example] = Vector.empty,    // user & assistant back and forth
    queryTemplate: String,                       // user template, aka immediateTask, user
    queryContextTemplate: Option[String] = None, // user template
    precognition: Option[String] = None,         // user
    outputFormatting: Option[String] = None,     // user
    prefill: Option[String] = None,              // assistant
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
