package supportbot
package rag
package vectorstore

import cats.effect.*
import cats.syntax.all.*
import fs2.{Chunk as _, *}
import supportbot.clickhouse.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import unindent.*
import java.util.Base64

final class ClickHouseVectorStore(client: ClickHouseClient[IO]) extends VectorStore[IO]:
  import ClickHouseVectorStore.*

  def store(index: Vector[Embedding.Index]): IO[Unit] =
    index.headOption
      .traverse: embedding =>
        documentEmbeddingsExists(embedding.documentId, embedding.documentVersion).ifM(
          IO.println(s"Embeddings for document ${embedding.documentId} already exists. Skipping the insertion."),
          storeEmbeddings(index)
        )
      .void

  private def storeEmbeddings(embeddings: Vector[Embedding.Index]): IO[Unit] =
    val values = embeddings
      .map: embedding =>
        import embedding.*

        val metadata = s"{${chunk.metadata.toVector.map((k, v) => s"'$k':'$v'").mkString(", ")}}"
        val embeddings = s"[${value.mkString(", ")}]"
        val encodedValue = s"'${base64TextEncode(chunk.text)}'"

        s"('$documentId', $documentVersion, $fragmentIndex, ${chunk.index}, $encodedValue, $metadata, $embeddings)"
      .mkString(",\n")

    val insertQuery =
      i"""
      INSERT INTO embeddings (*) VALUES
      ${values}
      """

    client
      .executeQuery(insertQuery)
      .productR(IO.println(s"Stored ${embeddings.size} embeddings."))

  def documentEmbeddingsExists(documentId: String, documentVersion: Int): IO[Boolean] =
    client
      .streamQueryTextLines:
        i"""
        SELECT
         EXISTS(
          SELECT 
           document_id, 
           document_version 
          FROM embeddings 
          WHERE document_id = '$documentId'
          AND document_version = $documentVersion
          LIMIT 1
         )
        """.stripMargin
      .compile
      .string
      .map(_.trim.toInt)
      .map(_ == 1)
      .flatMap: value =>
        IO.println(s"Document $documentId exists: $value").as(value)

  def retrieve(embedding: Embedding.Query): Stream[IO, Embedding.Retrieved] =
    client
      .streamQueryJson[ClickHouseRetrievedRow]:
        i"""
          WITH matched_embeddings AS (
            SELECT * FROM (
              SELECT 
                document_id,
                document_version, 
                fragment_index,
                value,
                metadata,
                cosineDistance(embedding, [${embedding.value.mkString(", ")}]) AS score 
              FROM embeddings
              ORDER BY score ASC
              LIMIT 3
            ) 
            LIMIT 1 BY document_id, document_version, fragment_index
          )
          SELECT 
            document_id,
            document_version,
            fragment_index,
            chunk_index,
            base64Decode(ae.value) AS value,
            metadata,
            score
          FROM matched_embeddings AS e
          INNER JOIN embeddings AS ae
          ON ae.document_id = e.document_id
          AND ae.document_version = e.document_version
          AND
              ae.fragment_index = e.fragment_index OR
              ae.fragment_index = e.fragment_index - 1 OR
              ae.fragment_index = e.fragment_index + 1
          ORDER BY document_id, document_version, fragment_index, chunk_index
          FORMAT JSONEachRow
        """
      .map: row =>
        Embedding.Retrieved(
          chunk = Chunk(text = row.value, index = row.chunk_index, metadata = row.metadata),
          value = embedding.value,
          documentId = row.document_id,
          documentVersion = row.document_version,
          fragmentIndex = row.fragment_index,
          score = row.score
        )

  def migrate(): IO[Unit] =
    // TODO: temp, move to migration scripts
    Vector(
      // i"""
      // DROP TABLE IF EXISTS embeddings;
      // """,
      i"""
      CREATE TABLE IF NOT EXISTS embeddings
      (
        document_id String,           -- unique identifier for the document, for now it's the file name
        document_version Int64,       -- version of the document, for now it's 1
        fragment_index Int64,         -- index of the fragment (like page) in the document
        chunk_index Int64,            -- index of the chunk in the fragment
        value String,                 -- base64 encoded value (likely just text) of the chunk
        metadata Map(String, String), -- any additional metadata of the chunk
        embedding Array(Float32),
        INDEX ann_idx embedding TYPE usearch('cosineDistance')
      )
      ENGINE = MergeTree()            -- not replacing, as we want to keep all embeddings for a given fragment_index
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

  private final case class ClickHouseRetrievedRow(
      document_id: String,
      document_version: Int,
      fragment_index: Int,
      chunk_index: Int,
      value: String,
      metadata: Map[String, String],
      score: Double
  ) derives ConfiguredJsonValueCodec
