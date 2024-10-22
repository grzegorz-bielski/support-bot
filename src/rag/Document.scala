package supportbot
package rag

object Document:
  final case class Ingested(
    id: DocumentId,
    name: DocumentName,
    version: DocumentVersion,
    fragments: Vector[Fragment],
  )

  final case class Fragment(
    index: Long,
    chunk: Chunk,
  )
