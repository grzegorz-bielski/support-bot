package supportbot

import java.util.UUID
import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

opaque type DocumentName <: String = String
object DocumentName:
  inline def apply(value: String): DocumentName = value

  given JsonValueCodec[DocumentName] = JsonCodecMaker.make

opaque type DocumentVersion <: Int = Int
object DocumentVersion:
  inline def apply(value: Int): DocumentVersion = value

  given JsonValueCodec[DocumentVersion] = JsonCodecMaker.make

extension (underlying: DocumentVersion)
  def next: DocumentVersion = DocumentVersion(underlying + 1)

opaque type DocumentId = UUID
object DocumentId:
  inline def apply(value: UUID): DocumentId = value
  def of: IO[DocumentId]                    = IO.randomUUID

  given JsonValueCodec[DocumentId] = JsonCodecMaker.make

final case class Chunk(
  text: String,
  index: Long,
  metadata: Map[String, String] = Map.empty,
):
  def toEmbeddingInput: String =
    metadata.map((k, v) => s"$k: $v").mkString("\n") ++ s"\n$text"
