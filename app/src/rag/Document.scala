package supportbot
package rag

object Document:
  final case class Ingested(
    info: Info,
    fragments: Vector[Fragment],
  )

  final case class Info(
    id: DocumentId,
    contextId: ContextId,
    name: DocumentName,
    version: DocumentVersion,
    description: String,
    `type`: String,
    metadata: Map[String, String],
  )

  final case class Fragment(
    index: Long,
    chunk: Chunk,
  )
