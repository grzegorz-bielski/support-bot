package supportbot
package rag

final case class Chunk(text: String, metadata: Map[String, String] = Map.empty):
  def toEmbeddingInput: String =
    metadata.map((k, v) => s"$k: $v").mkString("\n") ++ s"\n$text"
