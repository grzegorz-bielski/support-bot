package supportbot
package rag
package vectorstore

import fs2.Stream

trait VectorStore[F[_]]:
  def store(index: Vector[Embedding.Index]): F[Unit]
  def retrieve(query: Embedding.Query): Stream[F, Embedding.Retrieved]
  def documentEmbeddingsExists(documentId: String, documentVersion: Int): F[Boolean]