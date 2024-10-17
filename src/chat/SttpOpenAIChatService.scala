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
import sttp.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel}
import sttp.openai.requests.completions.chat.message.*

final class SttpOpenAIChatService(openAIProtocol: OpenAI, model: Model)(using
  backend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
) extends ChatService[IO]:
  import SttpOpenAIChatService.*

  private val chatModel = ChatCompletionModel.CustomChatCompletionModel(model)

  private val choicesAmount = 1

  def chatCompletion(prompt: Prompt): Stream[IO, ChatChunkResponse] =
    Stream
      .eval:
        openAIProtocol
          .createStreamedChatCompletion[IO](createChatBody(prompt))
          .send(backend)
          .map(_.body)
          .rethrow
      .flatten
      .map: response =>
        new ChatChunkResponse:
          def contentDeltas: String = 
            // assuming only one choice (configured by `choicesAmount`)
            response.choices.headOption.flatMap(_.delta.content).getOrElse("")

  private def createChatBody(prompt: Prompt) =
    ChatBody(
      model = chatModel,
      messages = bodyMessages(prompt),
      n = choicesAmount.some,
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
        ),
      ),
    )

object SttpOpenAIChatService:
  private def bodyMessages(prompt: Prompt): Vector[Message] =
    val systemMessage = Message.SystemMessage(
      content = Vector(
        prompt.taskContext,
        prompt.toneContext,
        prompt.taskDescription,
      ).map(_.getOrElse("")).mkString("\n"),
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
          prompt.outputFormatting,
        ).map(_.getOrElse("")).mkString("\n"),
      ),
    )

    val prefill = prompt.prefill.map(text => Message.AssistantMessage(text)).toVector

    (systemMessage +: examplesMessages :+ userMessage) ++ prefill
