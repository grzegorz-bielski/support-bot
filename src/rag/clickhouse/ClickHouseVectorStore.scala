package supportbot
package rag
package clickhouse

import cats.effect.*
import cats.syntax.all.*
import supportbot.clickhouse.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import unindent.*
import java.util.Base64

private[clickhouse] final case class ClickHouseRetrievedRow(
    document_id: String,
    document_version: Int,
    fragment_index: Int,
    value: String,
    metadata: Map[String, String],
    score: Double
) derives ConfiguredJsonValueCodec

final class ClickHouseVectorStore(client: ClickHouseClient[IO]) extends VectorStore[IO]:
  def store(index: Vector[Embedding.Index]): IO[Unit] =
    index.headOption
      .traverse: embedding =>
        documentEmbeddingExists(embedding).ifM(
          IO.println(s"Embeddings for document ${embedding.documentId} already exists. Skipping the insertion."),
          storeEmbeddings(index)
        )
      .void

  private def storeEmbeddings(index: Vector[Embedding.Index]): IO[Unit] =
    val values = index
      .map: embedding =>
        import embedding.*

        val metadata = s"{${chunk.metadata.toVector.map((k, v) => s"'$k':'$v'").mkString(", ")}}"
        val embeddings = s"[${value.mkString(", ")}]"
        val encodedValue = s"'${base64TextEncode(chunk.text)}'"

        s"('$documentId', $documentVersion, $fragmentIndex, $encodedValue, $metadata, $embeddings)"
      .mkString(",\n")

    // For some reason this only stores 56 embeddings ???
    val insertQuery =
      i"""
      INSERT INTO embeddings (*) VALUES
      ${values}
      """

    // println(insertQuery) // TODO: remove this

    client
      .executeQuery(insertQuery)
      .productR(IO.println(s"Stored ${index.size} embeddings."))

  private def documentEmbeddingExists(embedding: Embedding.Index): IO[Boolean] =
    client
      .streamQueryTextLines:
        s"""
        |SELECT
        | EXISTS(
        |  SELECT 
        |   document_id, 
        |   document_version 
        |  FROM embeddings 
        |  WHERE document_id = '${embedding.documentId}'
        |  AND document_version = ${embedding.documentVersion}
        |  LIMIT 1
        | )
        |""".stripMargin
      .compile
      .string
      .map(_.trim.toInt)
      .map(_ == 1)
      .flatMap: value =>
        IO.println(s"Document ${embedding.documentId} exists: $value").as(value)

  def retrieve(query: Embedding.Query): IO[Vector[Chunk]] =
    client
      .streamQueryJson[ClickHouseRetrievedRow]:
        i"""
        SELECT 
          document_id,
          document_version, 
          fragment_index,
          base64Decode(value) AS value,
          metadata,
          cosineDistance(embedding, [${query.value.mkString(", ")}]) AS score
        FROM embeddings
        ORDER BY score ASC
        LIMIT 3
        FORMAT JSONEachRow
        """
      .map(row =>
        println(row) // TODO: remove this
        Chunk(row.value, row.metadata)
      )
      .compile
      .toVector

  // TODO:
  //   - join on document_id and fragment_index
  //     s"""
  //     |WITH matched_embeddings AS (
  //     | SELECT(
  //     |  SELECT
  //     |   document_id,
  //     |   document_version,
  //     |   fragment_index,
  //     |   base64Decode(value) AS value,
  //     |   metadata,
  //     |   cosineDistance(embedding, [${query.value.mkString(", ")}]) AS score
  //     |  FROM embeddings
  //     |  ORDER BY score ASC
  //     |  LIMIT 3
  //     |  )
  //     | LIMIT 1 BY document_id, fragment_index -- assuming a single document_version
  //     |)
  //     |
  //     |SELECT *
  //     |FROM matched_embeddings as e
  //     |LEFT JOIN embeddings AS ae
  //     |ON ae.document_id = e.document_id
  //     |AND ae.document_version = e.document_version
  //     |AND ae.fragment_index IN (e.fragment_index - 1, e.fragment_index, e.fragment_index + 1)
  //     |FORMAT JSONEachRow
  //     |""".stripMargin

  def migrate(): IO[Unit] =
    // TODO: temp, move to migration scripts
    Vector(
      i"""
      DROP TABLE IF EXISTS embeddings;
      """,
      i"""
      CREATE TABLE IF NOT EXISTS embeddings
      (
        document_id String,
        document_version Int64,
        fragment_index Int64,
        value String,
        metadata Map(String, String),
        embedding Array(Float32),
        INDEX ann_idx embedding TYPE usearch('cosineDistance')
      )
      ENGINE = ReplacingMergeTree()
      ORDER BY (document_id, document_version, fragment_index)
      """
    ).traverse_(client.executeQuery)

  private def base64TextEncode(input: String): String =
    val charset = "UTF-8"
    val encoder = Base64.getEncoder // RFC4648 as on the decoder side in CH
    val encodedBytes = encoder.encode(input.getBytes(charset))

    String(encodedBytes, charset)

object ClickHouseVectorStore:
  def sttpBased(config: ClickHouseClient.Config)(using SttpBackend): ClickHouseVectorStore =
    ClickHouseVectorStore(SttpClickHouseClient(config))
