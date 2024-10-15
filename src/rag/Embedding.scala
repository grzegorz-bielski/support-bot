package supportbot
package rag

object Embedding:
  // embedding to be stored and indexed in the vector store
  final case class Index(
      chunk: Chunk,
      value: Vector[Float],
      documentId: String,
      documentVersion: Int,
      fragmentIndex: Int
  )

  // embedding retrieved from the vector store
  final case class Retrieved(
      chunk: Chunk,
      value: Vector[Float],
      documentId: String,
      documentVersion: Int,
      fragmentIndex: Int,
      score: Double
  )

  // embedding from the user query
  final case class Query(
      chunk: Chunk,
      value: Vector[Float]
  )
