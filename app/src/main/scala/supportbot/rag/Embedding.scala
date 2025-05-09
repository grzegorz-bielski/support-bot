package supportbot
package rag

import unindent.*

object Embedding:
  /** Embedding to be stored and indexed in the vector store
    */
  final case class Index(
    chunk: Chunk,
    value: Vector[Float],
    documentId: DocumentId,
    contextId: ContextId,
    fragmentIndex: Long,
  )

  /** Embedding retrieved from the vector store
    */
  final case class Retrieved(
    chunk: Chunk,
    value: Vector[Float],
    documentId: DocumentId,
    contextId: ContextId,
    fragmentIndex: Long,
    score: Double,
  ):
    override def toString: String = 
      i"""
      Retrieved(
        chunk: $chunk,
        value: [...],
        documentId: $documentId, 
        contextId: $contextId, 
        fragmentIndex: $fragmentIndex, 
        score: $score)
      """"

  /** Embedding from the user query
    */
  final case class Query(
    chunk: Chunk,
    contextId: ContextId,
    value: Vector[Float],
  )
