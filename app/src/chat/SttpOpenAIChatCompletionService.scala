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

final class SttpOpenAIChatCompletionService(using
  backend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
  openAIProtocol: OpenAI,
) extends ChatCompletionService[IO]:
  import SttpOpenAIChatCompletionService.*

  private val choicesAmount = 1

  def chatCompletion(prompt: Prompt, model: Model): Stream[IO, ChatChunkResponse] =
    Stream
      .eval:
        openAIProtocol
          .createStreamedChatCompletion[IO](createChatBody(prompt, model))
          .send(backend)
          .map(_.body)
          .rethrow
      .flatten
      .map: response =>
        new ChatChunkResponse:
          def contentDeltas: String =
            // assuming only one choice (configured by `choicesAmount`)
            response.choices.headOption.flatMap(_.delta.content).getOrElse("")

  private def createChatBody(prompt: Prompt, model: Model) =
    ChatBody(
      model = ChatCompletionModel.CustomChatCompletionModel(model.name),
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

object SttpOpenAIChatCompletionService:
  private def bodyMessages(prompt: Prompt): Vector[Message] =
    val renderedPrompt = prompt.render  

    val systemMessage = 
      renderedPrompt.system.map(text => Message.SystemMessage(text)).toVector

    val examplesMessages = renderedPrompt.examples.map:
      case PromptTemplate.Example.User(text)      => Message.UserMessage(Content.TextContent(text))
      case PromptTemplate.Example.Assistant(text) => Message.AssistantMessage(text)

    val userMessage = Message.UserMessage(
      content = Content.TextContent(renderedPrompt.user),
    )

    val assistant = renderedPrompt.assistant.map(text => Message.AssistantMessage(text)).toVector

    (systemMessage ++ examplesMessages :+ userMessage) ++ assistant
