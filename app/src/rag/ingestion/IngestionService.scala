package supportbot
package rag
package ingestion

import cats.effect.*
import fs2.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.*

import supportbot.rag.vectorstore.*

trait IngestionService[F[_]]:
  def ingest(input: IngestionService.Input): F[Document.Ingested]
object IngestionService:
  final case class Input(
    contextId: ContextId,
    documentName: DocumentName,
    embeddingsModel: Model,
    content: Stream[IO, Byte],
  )

final class ClickHouseIngestionService(using
  documentRepository: DocumentRepository[IO],
  embeddingsService: EmbeddingService[IO],
  vectorStoreRepository: VectorStoreRepository[IO],
  logger: Logger[IO],
) extends IngestionService[IO]:
  def ingest(input: IngestionService.Input): IO[Document.Ingested] =
    import input.*

    // TODO: this has a lot of moving pieces, maybe it can be done in more atomic way?

    for
      documentId <- DocumentId.of

      documentVersion <- getDocumentVersion(documentId, contextId, documentName)

      documentFragments <- LangChain4jIngestion.loadFrom(content, maxTokens = embeddingsModel.contextLength)
      _                 <- info"Document $documentId: loaded ${documentFragments.size} fragments from the document."

      documentInfo = Document.Info(
                       id = documentId,
                       contextId = contextId,
                       name = documentName,
                       description = "",
                       version = documentVersion,
                       `type` = "PDF", // TODO: infer from content
                       metadata = Map.empty,
                     )
      document     = Document.Ingested(
                       info = documentInfo,
                       fragments = documentFragments,
                     )

      _ <- documentRepository.createOrUpdate(document.info)
      _ <- info"Document $documentId metadata persisted."

      indexEmbeddings <- embeddingsService.createIndexEmbeddings(document, model = embeddingsModel)
      _               <- info"Document $documentId: created ${indexEmbeddings.size} embeddings."

      _ <- vectorStoreRepository.store(indexEmbeddings)
      _ <- info"Document $documentId: embeddings persisted."
    yield document

  private def getDocumentVersion(
    documentId: DocumentId,
    contextId: ContextId,
    documentName: DocumentName,
  ): IO[DocumentVersion] =
    for
      maybeDocumentWithSameName       <- documentRepository.get(contextId, documentName)
      documentVersion: DocumentVersion = maybeDocumentWithSameName.fold(DocumentVersion(1))(_.version.next)
      _                               <-
        val logContent = maybeDocumentWithSameName
          .map(_.id)
          .fold("name is unique")(id => s"doc $id has the same name, increased version number: $documentVersion")

        info"Document $documentId: $logContent."
    yield documentVersion

object ClickHouseIngestionService:
  def of(using
    DocumentRepository[IO],
    EmbeddingService[IO],
    VectorStoreRepository[IO],
  ): IO[ClickHouseIngestionService] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseIngestionService()
