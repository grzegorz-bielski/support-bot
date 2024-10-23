//> using scala 3.5.1
//> using toolkit typelevel:0.1.28
//> using dep org.typelevel::kittens::3.4.0
//> using dep org.typelevel::log4cats-slf4j:2.7.0

//> using dep com.outr::scribe-slf4j2:3.15.0
//> using dep com.outr::scribe-file:3.15.0

//> using dep org.http4s::http4s-dsl::0.23.28
//> using dep org.http4s::http4s-ember-server::0.23.28
//> using dep org.http4s::http4s-scalatags::0.25.2

//> using dep com.lihaoyi::scalatags::0.13.1

//> using dep com.softwaremill.sttp.openai::fs2:0.2.4
//> using dep com.softwaremill.sttp.openai::core:0.2.4
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M17

//> using dep com.github.haifengl::smile-scala:3.1.1

//> using dep dev.langchain4j:langchain4j:0.35.0
//> using dep dev.langchain4j:langchain4j-document-parser-apache-tika:0.35.0

///> using dep org.apache.pdfbox:pdfbox:3.0.3, langchain4j-document-parser-apache-tika uses different version

//> using dep com.davegurnell::unindent:1.8.0

//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core::2.30.15
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.30.15

//> using test.dep com.dimafeng::testcontainers-scala-munit::0.41.4


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
import supportbot.rag.ingestion.*
import supportbot.rag.vectorstore.*
import supportbot.chat.*
import supportbot.home.*
import supportbot.clickhouse.*
import java.util.UUID

object SupportBot extends ResourceApp.Forever:
  def run(args: List[String]): Resource[IO, Unit] =
    for
      _                               <- AppLogger.configure.toResource
      given SttpBackend               <- SttpBackend.resource
      given ClickHouseClient[IO]       = SttpClickHouseClient(
                                           ClickHouseClient.Config(
                                             url = "http://localhost:8123",
                                             username = "default",
                                             password = "default",
                                           ),
                                         )
      _                               <- ClickHouseMigrator
                                           .migrate(
                                             ClickHouseMigrator.Config(
                                               databaseName = "default",
                                               fresh = true,
                                             ),
                                           )
                                           .toResource
      given VectorStoreRepository[IO] <- ClickHouseVectorStore.of.toResource
      given OpenAI                     = OpenAI("ollama", uri"http://localhost:11434/v1")
      given ChatService[IO]            = SttpOpenAIChatService(
                                           model = Model.Llama31,
                                         )
      given EmbeddingService[IO]       = SttpOpenAIEmbeddingService(
                                           model = Model.SnowflakeArcticEmbed,
                                         )

      // offline - parsing and indexing
      _                               <- createLocalPdfEmbeddings(
                                           File("./content/SAFE3 - Support Guide-v108-20240809_102738.pdf"),
                                         ).toResource

      chatController <- ChatController.of()

      _ <- httpApp(
             controllers = Vector(
               chatController,
               HomeController,
             ),
           )
    yield ()

  // TODO: move to some ingestion service
  def createLocalPdfEmbeddings(
    file: File,
  )(using vectorStore: VectorStoreRepository[IO], embeddingService: EmbeddingService[IO]) =
    // TODO: hardcoded
    val documentId      = DocumentId(UUID.fromString("f47b3b3e-0b3b-4b3b-8b3b-3b3b3b3b3b3b"))
    val documentName    = DocumentName(file.getName)
    val documentVersion = DocumentVersion(1)

    vectorStore
      .documentEmbeddingsExists(documentId)
      .ifM(
        IO.println(s"Embeddings for document $documentId already exists. Skipping the chunking and indexing."),
        for
          _               <- IO.println("Chunking PDF")
          // fragments       <- LocalPDFDocumentLoader.loadPDF(file)
          fragments      <- LocalLangChain4jIngestion.loadPDF(file.toPath, Model.SnowflakeArcticEmbed.contextLength)
          fragmentsToEmbed = fragments
          _               <- IO.println(s"Creating embeddings for ${fragmentsToEmbed.size} chunks. It may take a while...")

          document         = Document.Ingested(
                               id = documentId,
                               name = documentName,
                               version = documentVersion,
                               fragments = fragmentsToEmbed,
                             )
          // _ <- IO.println(s"Fragments: $fragmentsToEmbed")
          indexEmbeddings <- embeddingService.createIndexEmbeddings(document)
          _               <- IO.println(s"Created ${indexEmbeddings.size} embeddings.")
          _               <- vectorStore.store(indexEmbeddings)
        yield (),
      )
