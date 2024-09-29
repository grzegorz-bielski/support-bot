package supportbot

import cats.syntax.all.*
import cats.effect.*
import fs2.Stream
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.client4.*
import sttp.capabilities.fs2.*
import sttp.model.Uri.*
import sttp.openai.OpenAI
import sttp.openai.streaming.fs2.*
import sttp.openai.OpenAIExceptions.OpenAIException
import sttp.openai.requests.completions.chat.ChatRequestResponseData.ChatResponse
import sttp.openai.requests.completions.chat.ChatRequestBody.{ChatBody, ChatCompletionModel}
import sttp.openai.requests.completions.chat.message.*
import smile.nlp.*
import sttp.openai.requests.embeddings.EmbeddingsRequestBody.*
import sttp.openai.requests.embeddings.EmbeddingsResponseBody.*

final class LLMService(backend: WebSocketStreamBackend[IO, Fs2Streams[IO]]):
  val openAI = OpenAI("ollama", uri"http://localhost:11434/v1")

  def bodyMessages(query: String, context: String) = Seq(
    Message.SystemMessage(
      content = Vector(
        "You are an expert Q&A system that is trusted around the world.",
        "Always answer the query using the provided, context information, and not prior knowledge",
        "Some rules to follow:",
        "1. Never directly reference the given context in your answer.",
        "2. Avoid statements like 'Based on the context, ...' or 'The context information...' or anything along those lines."
      ).mkString("\n")
    ),
    Message.UserMessage(
      content = Content.TextContent(
        Vector(
          "Context information is below.",
          "---------------------",
          context,
          "---------------------",
          "Given the context information and not prior knowledge, answer the query.",
          s"Query: $query",
          "Answer:"
        ).mkString("\n")
      )
    )
  )

  def chatRequestBody(query: String, context: String): ChatBody = ChatBody(
    model = ChatCompletionModel.CustomChatCompletionModel("llama3.1"),
    messages = bodyMessages(query, context),
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

  def createEmbeddings(input: EmbeddingsInput) =
    openAI
      .createEmbeddings(
        EmbeddingsBody(
          model = EmbeddingsModel.CustomEmbeddingsModel("snowflake-arctic-embed"),
          input = input
        )
      )
      .send(backend)
      .map(_.body)
      .rethrow

  def createIndexEmbeddings(document: Document): IO[Vector[Embedding.Index]] =
    createEmbeddings(
      EmbeddingsInput.MultipleInput(
        document.fragments.map(_.chunk.toEmbeddingInput)
      )
    )
      .map: embeddingResponse =>
        document.fragments
          .zip(embeddingResponse.data)
          .map: (fragment, value) =>
            Embedding.Index(
              chunk = fragment.chunk,
              value = value.embedding.toVector,
              documentId = document.id,
              fragmentIndex = fragment.index
            )

  def createQueryEmbeddings(chunk: Chunk): IO[Embedding.Query] =
    createEmbeddings(EmbeddingsInput.SingleInput(chunk.toEmbeddingInput))
      .map: response =>
        Embedding.Query(
          // TODO: assuming that single chunk will product one embedding, but we should validate it
          value = response.data.head.embedding.toVector,
          chunk = chunk
        )

  def runChatCompletion(query: String, context: String) =
    openAI
      .createStreamedChatCompletion[IO](chatRequestBody(query, context))
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

object LLMService:
  def resource: Resource[IO, LLMService] =
    HttpClientFs2Backend
      .resource[IO]()
      .map: backend =>
        LLMService(backend)
