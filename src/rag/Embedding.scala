package supportbot
package rag

enum Embedding:
  case Index(
      chunk: Chunk,
      value: Vector[Float],
      documentId: String,
      documentVersion: Int,
      fragmentIndex: Int
  )

  case Query(
      chunk: Chunk,
      value: Vector[Float]
  )
