//> using scala 3.5.1
//> using toolkit typelevel:0.1.28
//> using dep com.lihaoyi::scalatags::0.13.1
//> using dep org.http4s::http4s-dsl::0.23.28
//> using dep org.http4s::http4s-ember-server::0.23.28
//> using dep org.http4s::http4s-scalatags::0.25.2
//> using dep com.softwaremill.sttp.openai::fs2:0.2.4
//> using dep com.softwaremill.sttp.openai::core:0.2.4
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M17
//> using dep com.github.haifengl::smile-scala:3.1.1
//> using dep org.apache.pdfbox:pdfbox:3.0.3
//> using dep com.davegurnell::unindent:1.8.0
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core::2.30.15
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.30.15
//> using dep org.typelevel::log4cats-slf4j:2.7.0
//> using dep com.outr::scribe-slf4j2:3.15.0
//> using dep com.outr::scribe-file:3.15.0

package supportbot

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.openai.OpenAI
import sttp.model.Uri.*
import java.io.File

import supportbot.rag.*
import supportbot.rag.vectorstore.*
import supportbot.chat.*
import supportbot.clickhouse.*

object SupportBot extends ResourceApp.Forever:
  def run(args: List[String]): Resource[IO, Unit] =
    for
      _                         <- AppLogger.configure.toResource
      given SttpBackend         <- SttpBackend.resource
      clickHouseVectorStore     <- ClickHouseVectorStore
                                     .sttpBased(
                                       ClickHouseClient.Config(
                                         url = "http://localhost:8123",
                                         username = "default",
                                         password = "default",
                                       ),
                                     )
                                     .toResource
      given VectorStore[IO]      = clickHouseVectorStore
      _                         <- clickHouseVectorStore.migrate().toResource
      openAIProtocol             = OpenAI("ollama", uri"http://localhost:11434/v1")
      given ChatService[IO]      = SttpOpenAIChatService(openAIProtocol, model = Model("llama3.1"))
      given EmbeddingService[IO] = SttpOpenAIEmbeddingService(openAIProtocol, model = Model("snowflake-arctic-embed"))

      // TODO: move it to ingestion service
      // offline - parsing and indexing
      _ <- createLocalPdfEmbeddings(
             File("./resources/SAFE3 - Support Guide-v108-20240809_102738.pdf"),
           ).toResource

      chatController <- ChatController.of()

      _ <- httpApp(controllers = chatController)
    yield ()

  // TODO: move to some ingestion service
  def createLocalPdfEmbeddings(file: File)(using vectorStore: VectorStore[IO], embeddingService: EmbeddingService[IO]) =
    // TODO: make this user input
    val documentId      = file.getName
    val documentVersion = 1

    vectorStore
      .documentEmbeddingsExists(documentId, documentVersion)
      .ifM(
        IO.println(s"Embeddings for document $documentId already exists. Skipping the chunking and indexing."),
        for
          _               <- IO.println("Chunking PDF")
          document        <- DocumentLoader.loadPDF(file, documentId, documentVersion)
          _               <- IO.println(s"Creating embeddings. It may take a while...")
          indexEmbeddings <- embeddingService.createIndexEmbeddings(document)
          _               <- IO.println(s"Created ${indexEmbeddings.size} embeddings.")
          _               <- vectorStore.store(indexEmbeddings)
        yield (),
      )
