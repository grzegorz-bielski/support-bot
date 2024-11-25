package supportbot
package rag
package vectorstore

import fs2.Stream

trait VectorStoreRepository[F[_]]:
  def store(index: Vector[Embedding.Index]): F[Unit]
  def retrieve(query: Embedding.Query, settings: RetrievalSettings): Stream[F, Embedding.Retrieved]
  def delete(contextId: ContextId, documentId: DocumentId): F[Unit]
  def documentEmbeddingsExists(contextId: ContextId, documentId: DocumentId): F[Boolean]
