package supportbot

import java.util.UUID
import cats.effect.IO

opaque type Model <: String = String
object Model:
  inline def apply(value: String): Model = value

opaque type DocumentName <: String = String
object DocumentName:
  inline def apply(value: String): DocumentName = value

opaque type DocumentVersion <: Int = Int
object DocumentVersion:
  inline def apply(value: Int): DocumentVersion = value

opaque type DocumentId = UUID
object DocumentId:
  inline def apply(value: UUID): DocumentId = value
  def of: IO[DocumentId] = IO.randomUUID

final case class Chunk(
  text: String,
  index: Long,
  metadata: Map[String, String] = Map.empty,
):
  def toEmbeddingInput: String =
    metadata.map((k, v) => s"$k: $v").mkString("\n") ++ s"\n$text"
