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
          prompt, 
          chat_model, 
          embeddings_model
        FROM contexts 
        WHERE id = toUUID('$id')
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
          prompt, 
          chat_model, 
          embeddings_model
        FROM contexts 
        FORMAT JSONEachRow
        """
      .evalMap: row => 
        IO.fromEither(row.asContextInfo)
      .compile
      .toVector

  override def createOrUpdate(info: ContextInfo): IO[Unit] =
    // should be merged by ReplacingMergeTree on sorting key duplicates, but not at once
    client.executeQuery:
      i"""
      INSERT INTO contexts (
        id,
        name, 
        description, 
        prompt,
        chat_model,
        embeddings_model
      ) 
      VALUES (
        toUUID('${info.id}'),
        '${info.name}',
        '${info.description}', 
        '${writeToString(info.prompt)}',
        '${writeToString(info.chatModel)}',
        '${writeToString(info.embeddingsModel)}'
      )
      """
      // TODO: do not stringify the values, encode to String, or use new JSON type
  override def delete(id: ContextId): IO[Unit] =
    client.executeQuery:
      i"""DELETE FROM contexts WHERE id = toUUID('$id')"""

  // private def contextExists(id: ContextId): IO[Boolean] =
  //   client
  //     .streamQueryTextLines:
  //       i"""
  //       SELECT
  //        EXISTS(
  //         SELECT id 
  //         FROM contexts 
  //         WHERE id = toUUID('$id')
  //         LIMIT 1
  //        )
  //       """
  //     .compile
  //     .string
  //     .map(_.trim.toInt)
  //     .map(_ == 1)
  //     .flatMap: value =>
  //       info"Context $id exists: $value".as(value)

object ClickHouseContextRepository:
  def of(using client: ClickHouseClient[IO]): IO[ClickHouseContextRepository] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseContextRepository(client)

  private final case class ContextInfoRetrievedRow(
    id: ContextId,
    name: String,
    description: String,
    prompt: String,
    chat_model: String,
    embeddings_model: String,
  ) derives ConfiguredJsonValueCodec:
    def asContextInfo: Either[Throwable, ContextInfo] =
      Try:
        this.into[ContextInfo]
          .withFieldRenamed(_.chat_model, _.chatModel)
          .withFieldRenamed(_.embeddings_model, _.embeddingsModel)
          .withFieldConst(_.prompt, readFromString[Prompt](prompt))
          .withFieldConst(_.chatModel, readFromString[Model](chat_model))
          .withFieldConst(_.embeddingsModel, readFromString[Model](embeddings_model))
          .transform
      .toEither
