package supportbot
package chat

import cats.syntax.all.*
import cats.effect.*
import fs2.Stream
import sttp.client4.*
import sttp.capabilities.fs2.*
import sttp.model.Uri.*
import sttp.openai.OpenAI
import sttp.openai.streaming.fs2.*
import sttp.openai.OpenAIExceptions.OpenAIException
import sttp.openai.requests.completions.chat.ChatRequestResponseData.ChatResponse
import sttp.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel}
import sttp.openai.requests.completions.chat.message.*

type Example = ("User" | "Assistant", String)

/** A prompt template.
  *
  * Based on
  * https://github.com/anthropics/courses/blob/master/prompt_engineering_interactive_tutorial/Anthropic%201P/09_Complex_Prompts_from_Scratch.ipynb
  */
final case class Prompt(
    taskContext: Option[String] = None, // system
    toneContext: Option[String] = None, // system
    taskDescription: Option[String] = None, // system
    examples: Vector[Example] = Vector.empty, // user & assistant back and forth
    queryContext: Option[String] = None, // user
    query: String, // aka immediateTask, user
    precognition: Option[String] = None, // user
    outputFormatting: Option[String] = None, // user
    prefill: Option[String] = None // assistant
)

trait ChatService[F[_]]:
  def runChatCompletion(prompt: Prompt): F[Unit]

final class SttpOpenAIChatService(openAIProtocol: OpenAI, model: Model)(using
    backend: WebSocketStreamBackend[IO, Fs2Streams[IO]]
) extends ChatService[IO]:
  import SttpOpenAIChatService.*
  val chatModel = ChatCompletionModel.CustomChatCompletionModel(model)

  def runChatCompletion(prompt: Prompt): IO[Unit] =
    openAIProtocol
      .createStreamedChatCompletion[IO](
        ChatBody(
          model = chatModel,
          messages = bodyMessages(prompt),
          tools = Some(
            Seq(
              // Tool.FunctionTool(
              //   description = "This tool will return the sum of two numbers",
              //   name = "sum",
              //   parameters = Map(
              //     "number1" -> """{ "type": "number" }""",
              //     "number2" -> """{ "type": "number" }"""
              //   )
              // )
            )
          )
        )
      )
      .send(backend)
      .map(_.body)
      .flatMap:
        case Left(exception) => IO.println(s"error: ${exception.getMessage}")
        case Right(stream) =>
          stream
            .evalTap: chatChunkResponse =>
              val formattedChunk =
                chatChunkResponse.choices.map(_.delta.content.mkString).mkString
              IO.print(formattedChunk)
            .compile
            .drain

object SttpOpenAIChatService:
  private def bodyMessages(prompt: Prompt): Vector[Message] =
    val systemMessage = Message.SystemMessage(
      content = Vector(
        prompt.taskContext,
        prompt.toneContext,
        prompt.taskDescription
      ).map(_.getOrElse("")).mkString("\n")
    )

    val examplesMessages = prompt.examples.map:
      case ("User", text)      => Message.UserMessage(Content.TextContent(text))
      case ("Assistant", text) => Message.AssistantMessage(text)

    val userMessage = Message.UserMessage(
      content = Content.TextContent(
        Vector(
          prompt.queryContext,
          prompt.query.some,
          prompt.precognition,
          prompt.outputFormatting
        ).map(_.getOrElse("")).mkString("\n")
      )
    )

    val prefill = prompt.prefill.map(text => Message.AssistantMessage(text)).toVector

    (systemMessage +: examplesMessages :+ userMessage) ++ prefill
