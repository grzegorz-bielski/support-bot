package supportbot
package rag

object Document:
  final case class Ingested(
    documentId: DocumentId,
    fragments: Vector[Fragment],
  )

  final case class Fragment(
    index: Int,
    chunk: Chunk,
  )
