//> using scala 3.5.0
//> using toolkit typelevel:0.1.28
//> using dep com.softwaremill.sttp.openai::fs2:0.2.3
//> using dep com.softwaremill.sttp.openai::core:0.2.3
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M17
//> using dep com.github.haifengl::smile-scala:3.1.1
//> using dep org.apache.pdfbox:pdfbox:3.0.3

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

// type Embeddings = Vector[(Chunk, EmbeddingData)]

final case class Chunk(text: String, metadata: Map[String, String] = Map.empty):
  def toEmbeddingInput: String =
    metadata.map((k, v) => s"$k: $v").mkString("\n") ++ s"\n$text"

final case class Document(
    id: String,
    fragments: Vector[DocumentFragment]
)

final case class DocumentFragment(
    index: Int,
    chunk: Chunk
)

object Document:
  def fromPDF(path: String): IO[Document] =
    IO.blocking(fromPDFUnsafe(path))

  private def fromPDFUnsafe(path: String): Document =
    val file = File(path)
    val fileName = file.getName
    val document = Loader.loadPDF(File(path))

    // 1 based index
    val allFragments = (1 to document.getNumberOfPages)
      .foldLeft(Vector.newBuilder[DocumentFragment]): (builder, i) =>
        val textStripper = new PDFTextStripper()
        textStripper.setStartPage(i)
        textStripper.setEndPage(i)

        val metadata = Map("page" -> i.toString, "file" -> fileName)

        builder.addAll:
          textStripper
            .getText(document)
            .sentences
            .toVector
            .map: value =>
              DocumentFragment(index = i, Chunk(value, metadata))
      .result()

    Document(id = fileName, allFragments)

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

  // https://github.com/haifengl/smile/blob/76f79ecf902d066fa005ba86f7d51f6c176df458/scala/src/main/scala/smile/classification/package.scala
  // https://platform.openai.com/docs/guides/embeddings/use-cases
  // https://github.com/ZhengRui/minRAG/blob/main/rag_min.py
  // https://github.com/openai/openai-cookbook/blob/main/examples/vector_databases/cassandra_astradb/Philosophical_Quotes_CQL.ipynb
  // https://medium.com/@shravankoninti/mastering-rag-a-deep-dive-into-text-splitting-fafeffdcc00d

  enum Embedding:
    case Index(
        chunk: Chunk,
        value: Vector[Double],
        documentId: String,
        fragmentIndex: Int
    )

    case Query(
        chunk: Chunk,
        value: Vector[Double]
    )

  object KNN:
    // https://en.wikipedia.org/wiki/Cosine_similarity
    def cosineSimilarity(vec1: Vector[Double], vec2: Vector[Double]): Double =
      val magnitude1 = math.sqrt(vec1.map(x => x * x).sum)
      val magnitude2 = math.sqrt(vec2.map(x => x * x).sum)

      dotProduct(vec1, vec2) / (magnitude1 * magnitude2)

    def dotProduct(u: Vector[Double], v: Vector[Double]): Double =
      (u lazyZip v).foldLeft(0d):
        case (acc, (ui, vi)) => acc + (ui * vi)

    def findKNearestNeighbors[T](
        data: Vector[(Vector[Double], T)],
        input: Vector[Double],
        k: Int
    ): Vector[(T, Double)] =
      data
        .map: (features, label) =>
          (label, cosineSimilarity(features, input)) // TODO: move to Ordering in custom `compare`
        .sortBy(-_._2)
        .take(k)

    // TODO: delegate to DB
    def retrieve(
        index: Vector[Embedding.Index], // assuming this comes from all the documents
        query: Embedding.Query // to be classified
    ): IO[Vector[Chunk]] =
      IO.pure:
        for
          (neighbor, _) <- findKNearestNeighbors(
            data = index.map(embedding => (embedding.value, embedding)),
            input = query.value,
            k = 3
          )
          // neighbor lookup window, like +/- 1 page
          fragmentsIndexRange =
            neighbor.fragmentIndex - 1 to neighbor.fragmentIndex + 1
          // full scan...
          neighboringChunk <- index.collect:
            case Embedding.Index(chunk, _, documentId, fragmentIndex)
                if neighbor.documentId == documentId && fragmentsIndexRange.contains(fragmentIndex) =>
              chunk
        yield neighboringChunk

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

  def run: IO[Unit] =
    for
      // offline - parsing and indexing
      _ <- IO.println("Chunking PDF")
      document <- Document.fromPDF("./resources/SAFE3 - Support Guide-v108-20240809_102738.pdf")
      _ <- IO.println(s"Creating embeddings. It may take a while...")
      indexEmbeddings <- createIndexEmbeddings(document)
      // TODO: store index embeddings on disk

      query = "How do I solve manual resolution with unresolved_games reason?"
      queryEmbeddings <- createQueryEmbeddings(Chunk(query))

      contextChunk <- KNN.retrieve(index = indexEmbeddings, query = queryEmbeddings)
      _ <- IO.println(s"Retrieved context: ${contextChunk.map(_.toEmbeddingInput)}")

      - <- IO.println("Asking for chat completion")
      res <- runChatCompletion(
        query = query,
        context = contextChunk.map(_.toEmbeddingInput).mkString("\n")
      )
    yield ()

object Main extends IOApp.Simple:
  def run =
    HttpClientFs2Backend
      .resource[IO]()
      .use: backend =>
        LLmService(backend).run
