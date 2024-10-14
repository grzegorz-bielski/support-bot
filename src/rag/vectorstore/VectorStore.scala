package supportbot
package rag
package vectorstore

trait VectorStore[F[_]]:
  def store(index: Vector[Embedding.Index]): F[Unit]
  def retrieve(query: Embedding.Query): F[Vector[Chunk]]
  def documentEmbeddingsExists(documentId: String, documentVersion: Int): F[Boolean]
