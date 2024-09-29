package supportbot

final case class Document(
    id: String,
    fragments: Vector[DocumentFragment]
)

final case class DocumentFragment(
    index: Int,
    chunk: Chunk
)
