package supportbot

opaque type Model <: String = String
object Model:
  inline def apply(value: String): Model = value

final case class DocumentId(name: String, version: Int)

final case class Chunk(
  text: String,
  index: Int,
  metadata: Map[String, String] = Map.empty,
):
  def toEmbeddingInput: String =
    metadata.map((k, v) => s"$k: $v").mkString("\n") ++ s"\n$text"
