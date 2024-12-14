package supportbot
package rag
package vectorstore

import cats.effect.*
import cats.syntax.all.*
import fs2.{Chunk as _, *}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import unindent.*
import java.util.UUID

import supportbot.clickhouse.*

final class ClickHouseVectorStore(client: ClickHouseClient[IO])(using Logger[IO]) extends VectorStoreRepository[IO]:
  import ClickHouseVectorStore.*

  def delete(contextId: ContextId, documentId: DocumentId): IO[Unit] =
    client
      .executeQuery:
        i"""
        DELETE FROM embeddings
        WHERE
          context_id = toUUID('$contextId') AND
          document_id = toUUID('$documentId')
        """
      .void

  def store(index: Vector[Embedding.Index]): IO[Unit] =
    index.headOption
      .traverse: embedding =>
        // TODO: maybe this should not be a part of the store method
        documentEmbeddingsExists(embedding.contextId, embedding.documentId).ifM(
          info"Embeddings for document ${embedding.documentId} already exists. Skipping the insertion.",
          storeEmbeddings(index),
        )
      .void

  private def storeEmbeddings(embeddings: Vector[Embedding.Index]): IO[Unit] =
    val values = embeddings
      .map: embedding =>
        import embedding.*

        val metadata        = chunk.metadata.toClickHouseMap
        val embeddings      = s"[${value.mkString(", ")}]"
        val embeddingsValue = chunk.text.toClickHouseString

        s"(toUUID('$contextId'), toUUID('$documentId'), $fragmentIndex, ${chunk.index}, $embeddingsValue, $metadata, $embeddings)"
      .mkString(",\n")

    val insertQuery =
      i"""
      INSERT INTO embeddings (context_id, document_id, fragment_index, chunk_index, value, metadata, embedding) VALUES
      ${values}
      """

    client.executeQuery(insertQuery) *>
      info"Stored ${embeddings.size} embeddings."

  def documentEmbeddingsExists(contextId: ContextId, documentId: DocumentId): IO[Boolean] =
    client
      .streamQueryTextLines:
        i"""
        SELECT
         EXISTS(
          SELECT document_id 
          FROM embeddings 
          WHERE 
            context_id = toUUID('$contextId') AND 
            document_id = toUUID('$documentId') 
          LIMIT 1
         )
        """.stripMargin
      .compile
      .string
      .map(_.trim.toInt)
      .map(_ == 1)
      .flatMap: value =>
        info"Document $documentId embedding exists: $value".as(value)

  def retrieve(embedding: Embedding.Query, settings: RetrievalSettings): Stream[IO, Embedding.Retrieved] =
    client
      .streamQueryJson[ClickHouseRetrievedRow]:
        i"""
          WITH matched_embeddings AS (
            SELECT * FROM (
              SELECT 
                document_id,
                context_id,
                fragment_index AS matched_fragment_index,
                chunk_index AS matched_chunk_index,
                value,
                metadata,
                cosineDistance(embedding, [${embedding.value.mkString(", ")}]) AS score
              FROM embeddings
              WHERE context_id = toUUID('${embedding.contextId}')
              ORDER BY score ASC
              LIMIT ${settings.topK}
            ) 
            LIMIT 1 BY document_id, matched_fragment_index
          )
          SELECT 
            document_id,
            context_id,
            ae.fragment_index as fragment_index,
            ae.chunk_index as chunk_index,
            matched_fragment_index,
            matched_chunk_index,
            ae.value AS value,
            ae.metadata as metadata,
            score
          FROM matched_embeddings AS e
          INNER JOIN embeddings AS ae
          ON 
            ae.context_id = e.context_id AND 
            ae.document_id = e.document_id
          WHERE
            fragment_index BETWEEN 
            matched_fragment_index - ${settings.fragmentLookupRange.lookBack} AND 
            matched_fragment_index + ${settings.fragmentLookupRange.lookAhead}
          ORDER BY toUInt128(document_id), fragment_index, chunk_index
          LIMIT 1 BY document_id, fragment_index
          FORMAT JSONEachRow
        """
      .map: row =>
        Embedding.Retrieved(
          documentId = DocumentId(row.document_id),
          contextId = ContextId(row.context_id),
          chunk = Chunk(text = row.value, index = row.chunk_index, metadata = row.metadata),
          value = embedding.value,
          fragmentIndex = row.fragment_index,
          score = row.score,
        )
      .evalTap(retrieved => info"$retrieved")

object ClickHouseVectorStore:
  def of(using client: ClickHouseClient[IO]): IO[ClickHouseVectorStore] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseVectorStore(client)

  private final case class ClickHouseRetrievedRow(
    document_id: UUID,
    context_id: UUID,
    fragment_index: Long,
    chunk_index: Long,
    value: String,
    metadata: Map[String, String],
    score: Double,
  ) derives ConfiguredJsonValueCodec
