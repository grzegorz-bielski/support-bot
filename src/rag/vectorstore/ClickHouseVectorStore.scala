package supportbot
package rag
package vectorstore

import cats.effect.*
import cats.syntax.all.*
import fs2.{Chunk as _, *}
import supportbot.clickhouse.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import unindent.*
import java.util.Base64
import java.util.UUID

final class ClickHouseVectorStore(client: ClickHouseClient[IO])(using Logger[IO]) extends VectorStoreRepository[IO]:
  import ClickHouseVectorStore.*

  def store(index: Vector[Embedding.Index]): IO[Unit] =
    index.headOption
      .traverse: embedding =>
        // TODO: maybe this should not be a part of the store method
        documentEmbeddingsExists(embedding.documentId).ifM(
          info"Embeddings for document ${embedding.documentId} already exists. Skipping the insertion.",
          storeEmbeddings(index),
        )
      .void

  private def storeEmbeddings(embeddings: Vector[Embedding.Index]): IO[Unit] =
    val values = embeddings
      .map: embedding =>
        import embedding.*

        val metadata     = s"{${chunk.metadata.toVector.map((k, v) => s"'$k':'$v'").mkString(", ")}}"
        val embeddings   = s"[${value.mkString(", ")}]"
        val encodedValue = s"'${base64TextEncode(chunk.text)}'"

        s"($documentId, $fragmentIndex, ${chunk.index}, $encodedValue, $metadata, $embeddings)"
      .mkString(",\n")

    val insertQuery =
      i"""
      INSERT INTO embeddings (*) VALUES
      ${values}
      """

    client.executeQuery(insertQuery) *>
      info"Stored ${embeddings.size} embeddings."

  def documentEmbeddingsExists(documentId: DocumentId): IO[Boolean] =
    client
      .streamQueryTextLines:
        i"""
        SELECT
         EXISTS(
          SELECT document_id 
          FROM embeddings 
          WHERE document_id = toUUID('$documentId') 
          LIMIT 1
         )
        """.stripMargin
      .compile
      .string
      .map(_.trim.toInt)
      .map(_ == 1)
      .flatMap: value =>
        info"Document $documentId exists: $value".as(value)

  def retrieve(embedding: Embedding.Query, options: RetrieveOptions): Stream[IO, Embedding.Retrieved] =
    client
      .streamQueryJson[ClickHouseRetrievedRow]:
        i"""
          WITH matched_embeddings AS (
            SELECT * FROM (
              SELECT 
                document_id,
                fragment_index,
                value,
                metadata,
                cosineDistance(embedding, [${embedding.value.mkString(", ")}]) AS score 
              FROM embeddings
              ORDER BY score ASC
              LIMIT ${options.topK}
            ) 
            LIMIT 1 BY document_id, fragment_index
          )
          SELECT 
            document_id,
            fragment_index,
            chunk_index,
            base64Decode(ae.value) AS value,
            metadata,
            score
          FROM matched_embeddings AS e
          INNER JOIN embeddings AS ae
          ON ae.document_id = e.document_id
          AND
              ae.fragment_index = e.fragment_index OR
              ae.fragment_index = e.fragment_index - 1 OR
              ae.fragment_index = e.fragment_index + 1
          ORDER BY toUInt128(document_id), fragment_index, chunk_index
          FORMAT JSONEachRow
        """
      .map: row =>
        Embedding.Retrieved(
          documentId = DocumentId(row.document_id),
          chunk = Chunk(text = row.value, index = row.chunk_index, metadata = row.metadata),
          value = embedding.value,
          fragmentIndex = row.fragment_index,
          score = row.score,
        )

  private def base64TextEncode(input: String): String =
    val charset      = "UTF-8"
    val encoder      = Base64.getEncoder // RFC4648 as on the decoder side in CH
    val encodedBytes = encoder.encode(input.getBytes(charset))

    String(encodedBytes, charset)

object ClickHouseVectorStore:
  def of(using client: ClickHouseClient[IO]): IO[ClickHouseVectorStore] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseVectorStore(client)

  private final case class ClickHouseRetrievedRow(
    document_id: UUID,
    fragment_index: Long,
    chunk_index: Long,
    value: String,
    metadata: Map[String, String],
    score: Double,
  ) derives ConfiguredJsonValueCodec

