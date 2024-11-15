package supportbot
package context

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
import scala.util.Try

import supportbot.clickhouse.*

import ClickHouseContextRepository.*

final class ClickHouseContextRepository(client: ClickHouseClient[IO])(using Logger[IO]) extends ContextRepository[IO]:
  def get(id: ContextId): IO[Option[ContextInfo]] =
    client
      .streamQueryJson[ContextInfoRetrievedRow]:
        i"""
        SELECT 
          id, 
          name, 
          description, 
          prompt_template, 
          chat_model, 
          embeddings_model,
          updated_at
        FROM contexts 
        WHERE id = toUUID('$id')
        ORDER BY updated_at DESC
        LIMIT 1
        FORMAT JSONEachRow
        """
      .evalMap: row => 
        IO.fromEither(row.asContextInfo)
      .compile
      .last

  def getAll: IO[Vector[ContextInfo]] =
    client
      .streamQueryJson[ContextInfoRetrievedRow]:
        i"""
        SELECT 
          id, 
          name, 
          description, 
          prompt_template, 
          chat_model, 
          embeddings_model,
          updated_at
        FROM contexts
        ORDER BY toUInt128(id), updated_at DESC
        LIMIT 1 BY id
        FORMAT JSONEachRow
        """
      .evalMap: row => 
        IO.fromEither(row.asContextInfo)
      .compile
      .toVector

  override def createOrUpdate(info: ContextInfo): IO[Unit] =
    val writerConfig = WriterConfig.withEscapeUnicode(true)

    // should be merged by ReplacingMergeTree on sorting key duplicates, but not at once
    client.executeQuery:
      i"""
      INSERT INTO contexts (
        id,
        name, 
        description, 
        prompt_template,
        chat_model,
        embeddings_model
      ) 
      VALUES (
        toUUID(${info.id.toString.toClickHouseString}),
        ${info.name.toClickHouseString},
        ${info.description.toClickHouseString}, 
        ${writeToString(info.promptTemplate, writerConfig).toClickHouseString},
        ${writeToString(info.chatModel, writerConfig).toClickHouseString},
        ${writeToString(info.embeddingsModel, writerConfig).toClickHouseString}
      )
      """
      // TODO: do not stringify the values, encode to String, or use new JSON type
  override def delete(id: ContextId): IO[Unit] =
    client.executeQuery:
      i"""DELETE FROM contexts WHERE id = toUUID('$id')"""

object ClickHouseContextRepository:
  def of(using client: ClickHouseClient[IO]): IO[ClickHouseContextRepository] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseContextRepository(client)

  private final case class ContextInfoRetrievedRow(
    id: ContextId,
    name: String,
    description: String,
    prompt_template: String,
    chat_model: String,
    embeddings_model: String,
  ) derives ConfiguredJsonValueCodec:
    def asContextInfo: Either[Throwable, ContextInfo] =
      Try:
        ContextInfo(
          id = id,
          name = name,
          description = description,
          promptTemplate = readFromString[PromptTemplate](prompt_template),
          chatModel = readFromString[Model](chat_model),
          embeddingsModel = readFromString[Model](embeddings_model),
        )
      .toEither
