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

//> using dep io.scalaland::chimney::1.5.0

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
import supportbot.context.*
import java.util.UUID

// TODO: context + documents CRUD

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
      given ContextRepository[IO]     <- ClickHouseContextRepository.of.toResource
      given DocumentRepository[IO]    <- ClickHouseDocumentRepository.of.toResource
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
  )(using 
    vectorStore: VectorStoreRepository[IO], 
    embeddingService: EmbeddingService[IO],
    contextRepository: ContextRepository[IO],
    documentRepository: DocumentRepository[IO],
    ) =
    // TODO: hardcoded
    val contextId = ContextId(UUID.fromString("f47b3b3e-0b3b-4b3b-8b3b-3b3b3b3b3b3b"))
    val documentId      = DocumentId(UUID.fromString("f47b3b3e-0b3b-4b3b-8b3b-3b3b3b3b3b3b"))
    val documentName    = DocumentName(file.getName)
    val documentVersion = DocumentVersion(1)

    
    vectorStore
      .documentEmbeddingsExists(documentId)
      .ifM(
        IO.println(s"Embeddings for document $documentId already exists. Skipping the chunking and indexing."),
        for
          _ <- IO.println("(Re)creating context and document")

          _ <- contextRepository.createOrUpdate(
                        ContextInfo(
                          id = contextId,
                          name = "Support",
                          description = "Support context",
                          prompt = Prompt(
                            query = "I need help with...",
                          ),
                          chatModel = Model.Llama31,
                          embeddingsModel = Model.SnowflakeArcticEmbed,
                        ),
                      )

          
          _               <- IO.println("Chunking PDF")
          // fragments       <- LocalPDFDocumentLoader.loadPDF(file)
          fragments       <- LocalLangChain4jIngestion.loadPDF(file.toPath, Model.SnowflakeArcticEmbed.contextLength)
          fragmentsToEmbed = fragments

          _ <- IO.println(s"Creating document $documentId with ${fragmentsToEmbed.size} chunks")
          document = Document.Ingested(
                          info = Document.Info(
                            id = documentId,
                            contextId = contextId,
                            name = documentName,
                            description = "Support document",
                            version = documentVersion,
                            `type` = "PDF",
                            metadata = Map.empty,
                          ),
                          fragments = fragmentsToEmbed,
                        )
          _ <- documentRepository.createOrUpdate(document.info)

          // _ <- IO.println(s"Fragments: $fragmentsToEmbed")
          _               <- IO.println(s"Creating embeddings for ${fragmentsToEmbed.size} chunks. It may take a while...")
          indexEmbeddings <- embeddingService.createIndexEmbeddings(document)
          _               <- IO.println(s"Created ${indexEmbeddings.size} embeddings.")
          _               <- vectorStore.store(indexEmbeddings)
        yield (),
      )


// Document f47b3b3e-0b3b-4b3b-8b3b-3b3b3b3b3b3b embedding exists: false    
// supportbot.clickhouse.ClickHouseClient$Error$QueryFailed: Code: 62. DB::Exception: Cannot parse expression of type Array(Float32) here: [-0.0051364605, -0.03613305, -0.0055216765, 0.03955842, 0.03655444, -0.01854114, 0.023542129, -0.018183189, -0.0041799336, -4.7423202E-4, 0.020579364, -0.012071: While executing ValuesBlockInputFormat. (SYNTAX_ERROR) (version 24.3.12.75 (official build))
