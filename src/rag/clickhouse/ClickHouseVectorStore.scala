package supportbot
package rag
package clickhouse

import cats.effect.*
import cats.syntax.all.*
import supportbot.clickhouse.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

private[clickhouse] final case class ClickHouseEmbeddedRow(
    document_id: String,
    fragment_index: Int,
    text: String,
    metadata: Map[String, String],
    embedding: Vector[Double]
) derives ConfiguredJsonValueCodec

private[clickhouse] final case class ClickHouseRetrieved(
    document_id: String,
    fragment_index: Int,
    text: String,
    metadata: Map[String, String],
    score: Double
) derives ConfiguredJsonValueCodec

final class ClickHouseVectorStore(client: ClickHouseClient[IO]) extends VectorStore[IO]:
  def store(index: Vector[Embedding.Index]): IO[Unit] =
    val values = index
      .map:
        case Embedding.Index(chunk, value, documentId, fragmentIndex) =>
          val metadata = s"{${chunk.metadata.toVector.map((k, v) => s"'$k':'$v'").mkString(", ")}}"
          val embeddings = s"[${value.mkString(", ")}]"
          val text = s"'${chunk.text}'"

          s"('$documentId', $fragmentIndex, $text, $metadata, $embeddings)"
      .mkString(", ")

    client.executeQuery:
      s"""
        |INSERT INTO embeddings
        |(document_id, fragment_index, text, metadata, embedding)
        |VALUES
        |${values}
        |""".stripMargin

  def retrieve(query: Embedding.Query): IO[Vector[Chunk]] =
    client
      .streamQuery[ClickHouseRetrieved]:
        s"""
        |SELECT 
        |   document_id, 
        |   fragment_index,
        |   text,
        |   metadata,
        |   cosineDistance(embedding, ${query.value.mkString(", ")}) AS score
        |FROM embeddings
        |ORDER BY score ASC
        |LIMIT 3
        |FORMAT JSONEachRow
        |""".stripMargin
      .map(row =>
        println(row) // TODO: remove this
        Chunk(row.text, row.metadata)
      )
      .compile
      .toVector

  def migrate(): IO[Unit] =
    Vector(
      """
        |CREATE TABLE IF NOT EXISTS embeddings
        |(
        |  document_id String,
        |  fragment_index Int64,
        |  text String,
        |  metadata Map(String, String),
        |  embedding Array(Float32),
        |  INDEX ann_idx embedding TYPE usearch('cosineDistance')
        |)
        |ENGINE = MergeTree()
        |ORDER BY document_id;
        |""".stripMargin
    ).traverse_(client.executeQuery)

object ClickHouseVectorStore:
  def sttpBased(config: ClickHouseClient.Config)(using SttpBackend): ClickHouseVectorStore =
    ClickHouseVectorStore(SttpClickHouseClient(config))


// TODO: rework document id
// supportbot.clickhouse.ClickHouseClient$Error$QueryFailed: Code: 62. DB::Exception: Cannot parse expression of type String here: 'Casino Backoffice and Regulatory Requirements – SAFE3 - Support Guide 3 19.1 Client certificates ............................................................: While executing ValuesBlockInputFormat. (SYNTAX_ERROR) (version 24.3.12.75 (official build))
