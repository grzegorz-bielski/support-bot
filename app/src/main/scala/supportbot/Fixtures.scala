package supportbot

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.*
import java.util.UUID
import fs2.io.file.{Files, Path, Flags}

import supportbot.rag.*
import supportbot.rag.ingestion.*
import supportbot.rag.vectorstore.*
import supportbot.clickhouse.*

// dev only test data
object Fixtures:
  def loadFixtures()(using
    VectorStoreRepository[IO],
    ContextRepository[IO],
    IngestionService[IO],
    DocumentRepository[IO],
    AppConfig,
  ) =
    IO.whenA(AppConfig.get.loadFixtures):
      Files[IO]
        .list(Path("./app/content"))
        .take(1) // only first file, so it's faster
        .evalMap: path =>
          IO.println(s"Processing file: $path") *> createLocalPdfEmbeddings(path)
        .compile
        .drain

  private def createLocalPdfEmbeddings(path: Path)(using
    vectorStore: VectorStoreRepository[IO],
    contextRepository: ContextRepository[IO],
    ingestionService: IngestionService[IO],
    documentRepository: DocumentRepository[IO],
  ) =
    // hardcoded
    val contextId       = ContextId(UUID.fromString("f47b3b3e-0b3b-4b3b-8b3b-3b3b3b3b3b3b"))
    val documentId      = DocumentId(UUID.fromString("f47b3b3e-0b3b-4b3b-8b3b-3b3b3b3b3b3b"))
    val documentName    = DocumentName(path.fileName.toString)
    val documentVersion = DocumentVersion(1)

    vectorStore
      .documentEmbeddingsExists(contextId, documentId)
      .ifM(
        IO.println(s"Embeddings for document $documentId already exists. Skipping chunking and indexing."),
        for
          _ <- IO.println("(Re)creating context and document")

          _ <- contextRepository.createOrUpdate(
                 ContextInfo.default(
                   id = contextId,
                   name = "Fixture bot",
                   description = "Support bot fixture context",
                 ),
               )

          fileContent = Files[IO].readAll(path, chunkSize = 4096, flags = Flags.Read)
          _          <- ingestionService.ingest(
                          IngestionService.Input(
                            contextId = contextId,
                            documentName = documentName,
                            embeddingsModel = Model.defaultEmbeddingsModel,
                            content = fileContent,
                          ),
                        )

          _        <- IO.println("Retrieving context")
          contexts <- contextRepository.getAll
          _        <- IO.println(s"Contexts: $contexts")

          _ <- IO.println("Retrieving documents")
          _ <- contexts.traverse: ctx =>
                 documentRepository
                   .getAll(ctx.id)
                   .flatMap: documents =>
                     IO.println(s"Documents for context ${ctx.id}: $documents")
        yield (),
      )
