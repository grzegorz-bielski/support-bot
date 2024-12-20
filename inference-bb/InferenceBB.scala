package supportbot
package bench

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.*

import fs2.*
import sttp.model.Uri.*
import sttp.openai.OpenAI
import sttp.openai.streaming.fs2.*
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.openai.OpenAIExceptions.OpenAIException
import sttp.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel}
import sttp.openai.requests.completions.chat.message.*
import sttp.openai.requests.embeddings.EmbeddingsRequestBody.*
import sttp.openai.requests.embeddings.EmbeddingsResponseBody.*
import scala.concurrent.duration.*

// OpenAPI inference backend smoke test bench

object InferenceBB extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    args match
      case "ollama" :: model :: Nil =>
        program(OpenAI("ollama", uri"http://localhost:11434/v1"), model)
      case "cortex" :: model :: Nil =>
        program(OpenAI("cortex", uri"http://localhost:39281/v1"), model)
      case _                        =>
        IO.println("Usage: InferenceBB ollama|cortex model").as(ExitCode.Error)

  private def program(protocol: OpenAI, model: String): IO[ExitCode] =
    HttpClientFs2Backend
      .resource[IO]()
      .use: backend =>
        val testEmbeddings = protocol
          .createEmbeddings(
            EmbeddingsBody(
              input = EmbeddingsInput.MultipleInput(
                List(
                  "Hello, world!",
                  "Goodbye, world!",
                ),
              ),
              model = EmbeddingsModel.CustomEmbeddingsModel(model),
            ),
          )
          .readTimeout(5.minutes)
          .send(backend)
          .flatMap: response =>
            IO.println(response).as(ExitCode.Success)

        testEmbeddings
