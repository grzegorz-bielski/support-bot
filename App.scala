//> using scala 3.5.0
//> using toolkit typelevel:0.1.28
//> using dep com.softwaremill.sttp.openai::fs2:0.2.3
//> using dep com.softwaremill.sttp.openai::core:0.2.3
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M17
//> using dep com.github.haifengl::smile-scala:3.1.1
//> using dep org.apache.pdfbox:pdfbox:3.0.3

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
import smile.classification
import sttp.openai.requests.embeddings.EmbeddingsRequestBody.*
import sttp.openai.requests.embeddings.EmbeddingsResponseBody.*

import scala.jdk.CollectionConverters.*
import java.io.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.Loader
// import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
// import org.apache.pdfbox.text.

type Embeddings = Vector[(Chunk, EmbeddingData)]

final case class Chunk(text: String, metadata: Map[String, String] = Map.empty):
  def toEmbeddingInput: String =
    metadata.map((k, v) => s"$k: $v").mkString("\n") ++ s"\n$text"

object Chunks:
  def fromPDF(path: String): IO[Vector[Chunk]] =
    IO.blocking(fromPDFUnsafe(path))

  private def fromPDFUnsafe(path: String): Vector[Chunk] =
    val file = File(path)
    val document = Loader.loadPDF(File(path))

    // 1 based index
    (1 to document.getNumberOfPages)
      .foldLeft(Vector.newBuilder[Chunk]): (builder, i) =>
        val textStripper = new PDFTextStripper()
        textStripper.setStartPage(i)
        textStripper.setEndPage(i)

        val chunks =
          textStripper
            .getText(document)
            .sentences
            .toVector
            .map:
              Chunk(_, Map("page" -> i.toString, "file" -> file.getName))

        builder.addAll(chunks)
      .result()

class LLmService(backend: WebSocketStreamBackend[IO, Fs2Streams[IO]]):
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
    // Message.UserMessage(
    //   content = Content.TextContent("Hi, I need to calculate the sum of 2 and 3")
    // )
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

  // KNN, cosine similarity
  def retrieve(
    queryEmbeddings: Embeddings,
    indexEmbeddings: Embeddings
  ): IO[String] = 
    // TODO: https://github.com/haifengl/smile/blob/76f79ecf902d066fa005ba86f7d51f6c176df458/scala/src/main/scala/smile/classification/package.scala
    // https://platform.openai.com/docs/guides/embeddings/use-cases
    // classification.knn(
    //   x = queryEmbeddings.map(_._2.embedding),
    // )
    ???

  def createEmbedding(chunks: Vector[Chunk]) =
    openAI
      .createEmbeddings(
        EmbeddingsBody(
          model = EmbeddingsModel.CustomEmbeddingsModel("snowflake-arctic-embed"),
          input = EmbeddingsInput.MultipleInput(chunks.map(_.toEmbeddingInput))
        )
      )
      .send(backend)
      .map(_.body)
      .rethrow
      .map: embeddingResponse =>
        chunks.zip(embeddingResponse.data)

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

  def run: IO[Unit] =
    // runChatCompletion
    for
      // offline - parsing and indexing
      _ <- IO.println("Chunking PDF")
      chunks <- Chunks.fromPDF("./resources/SAFE3 - Support Guide-v108-20240809_102738.pdf")
      _ <- IO.println(s"Chunked ${chunks.length} sentences")
      _ <- IO.println(s"Creating embeddings. It may take a while...")
      indexEmbeddings <- createEmbedding(chunks)
      // store indexEmbeddings in a database
      _ <- IO.println("Embeddings created")

      // online - chat completion
      query = "How do I solve manual resolution with unresolved_games reason?"
      queryEmbeddings <- createEmbedding(Vector(Chunk(query)))
      // retrieve indexEmbeddings from database
      context <- retrieve(queryEmbeddings, indexEmbeddings)

      _ <- runChatCompletion(query, context)

      // _ <- IO.println(queryEmbeddings)
    yield ()

    // createEmbedding(Vector(Chunk("I am a sentence"), Chunk("I am another sentence")))
    // .flatMap(IO.println)

object Main extends IOApp.Simple:
  def run =
    HttpClientFs2Backend
      .resource[IO]()
      .use: backend =>
        LLmService(backend).run
