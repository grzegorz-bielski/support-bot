package supportbot
package rag
package ingestion

trait IngestionService[F[_]]:
  def ingest(documentId: DocumentId): F[Document.Ingested]
