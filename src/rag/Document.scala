package supportbot
package rag

final case class Document(
    id: String,
    version: Int,
    fragments: Vector[DocumentFragment]
)

final case class DocumentFragment(
    index: Int,
    chunk: Chunk
)
