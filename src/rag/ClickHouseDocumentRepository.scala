package supportbot
package rag

import java.util.UUID
import cats.effect.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import unindent.*
import java.util.UUID
import java.time.*
import io.scalaland.chimney.dsl.*

import supportbot.clickhouse.*
import supportbot.context.*

import ClickHouseDocumentRepository.*

final class ClickHouseDocumentRepository(client: ClickHouseClient[IO])(using Logger[IO]) extends DocumentRepository[IO]:
  def getAll(contextId: ContextId): IO[Vector[Document.Info]] =
    client
      .streamQueryJson[IngestedDocumentRow]:
        i"""
        SELECT 
          id, 
          context_id, 
          name, 
          description, 
          version, 
          type, 
          embeddings_model, 
          metadata
        FROM documents 
        FORMAT JSONEachRow
        """
      .map(_.asDocumentInfo)
      .compile
      .toVector

  override def createOrUpdate(document: Document.Info): IO[Unit] =
    // should be merged by ReplacingMergeTree on sorting key duplicates, but not at once
    client.executeQuery:
      i"""
      INSERT INTO documents (
        id, 
        context_id, 
        name, 
        description, 
        version, 
        type,
        metadata
      ) VALUES (
        toUUID('${document.id}'),
        toUUID('${document.contextId}'),
        '${document.name}',
        '${document.description}',
        ${document.version},
        '${document.`type`}',
        ${document.metadata.toClickHouseMap}
      )
      """

  override def delete(id: DocumentId): IO[Unit] =
    client.executeQuery:
      i"""DELETE FROM documents WHERE id = toUUID('$id')"""

object ClickHouseDocumentRepository:
  def of(using client: ClickHouseClient[IO]): IO[ClickHouseDocumentRepository] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseDocumentRepository(client)

  private final case class IngestedDocumentRow(
    id: DocumentId,
    context_id: ContextId,
    name: DocumentName,
    version: DocumentVersion,
    description: String,
    `type`: String,
    metadata: Map[String, String],
  ) derives ConfiguredJsonValueCodec:
    def asDocumentInfo: Document.Info =
      this.into[Document.Info]
        .withFieldRenamed(_.context_id, _.contextId)
        .transform
