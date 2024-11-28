package supportbot

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
import io.scalaland.chimney.*
import scala.util.Try
import scala.util.control.NoStackTrace

import supportbot.clickhouse.*

import ClickHouseContextRepository.*
import ContextInfo.given

trait ContextRepository[F[_]]:
  def createOrUpdate(info: ContextInfo): F[Unit]
  def getAll: F[Vector[ContextInfo]]
  def get(contextId: ContextId): F[Option[ContextInfo]]
  def getByName(name: String): F[Vector[ContextInfo]]
  def delete(id: ContextId): F[Unit]

final class ClickHouseContextRepository(client: ClickHouseClient[IO])(using Logger[IO]) extends ContextRepository[IO]:
  private lazy val selectContextFragment =
    i"""
    SELECT 
      id, 
      name, 
      description, 
      prompt_template,
      retrieval_settings,
      chat_completion_settings,
      chat_model,
      embeddings_model,
      updated_at
    FROM contexts
    """

  def get(id: ContextId): IO[Option[ContextInfo]] =
    client
      .streamQueryJson[ContextInfoRetrievedRow]:
        i"""
        $selectContextFragment 
        WHERE id = toUUID('$id')
        ORDER BY updated_at DESC
        LIMIT 1
        FORMAT JSONEachRow
        """
      .evalMap: row =>
        IO.fromEither(row.asContextInfo)
      .compile
      .last

  // leverages the `context_name_projection`
  // `name` is not unique (!)
  def getByName(name: String): IO[Vector[ContextInfo]] =
    client
      .streamQueryJson[ContextInfoRetrievedRow]:
        i"""
        $selectContextFragment
        WHERE name = '$name'
        ORDER BY id, updated_at DESC
        LIMIT 1 BY id
        FORMAT JSONEachRow
        """
      .evalMap: row =>
        IO.fromEither(row.asContextInfo)
      .compile
      .toVector

  def getAll: IO[Vector[ContextInfo]] =
    client
      .streamQueryJson[ContextInfoRetrievedRow]:
        i"""
        $selectContextFragment
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
        retrieval_settings,
        chat_completion_settings,
        chat_model,
        embeddings_model
      ) 
      VALUES (
        toUUID(${info.id.toString.toClickHouseString}),
        ${info.name.toClickHouseString},
        ${info.description.toClickHouseString}, 
        ${writeToString(info.promptTemplate, writerConfig).toClickHouseString},
        ${writeToString(info.retrievalSettings, writerConfig).toClickHouseString},
        ${writeToString(info.chatCompletionSettings, writerConfig).toClickHouseString},
        ${writeToString(info.chatModel, writerConfig).toClickHouseString},
        ${writeToString(info.embeddingsModel, writerConfig).toClickHouseString}
      )
      """
      // TODO: do not stringify the values, encode to String, or use new JSON type
  override def delete(id: ContextId): IO[Unit]             =
    client.executeQuery:
      i"""DELETE FROM contexts WHERE id = toUUID('$id')"""

object ClickHouseContextRepository:
  def of(using client: ClickHouseClient[IO]): IO[ClickHouseContextRepository] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ClickHouseContextRepository(client)

  inline given CodecMakerConfig =
    CodecMakerConfig.withFieldNameMapper(JsonCodecMaker.enforce_snake_case)

  private final case class ContextInfoRetrievedRow(
    id: ContextId,
    name: String,
    description: String,
    promptTemplate: String,
    retrievalSettings: String,
    chatCompletionSettings: String,
    chatModel: String,
    embeddingsModel: String,
  ) derives ConfiguredJsonValueCodec:
    def asContextInfo: Either[Throwable, ContextInfo] =
      this
        .intoPartial[ContextInfo]
        .transform
        .asEitherErrorPathMessageStrings
        .leftMap: errors =>
          ContextRetrievalError(errors.mkString(", "))

  final class ContextRetrievalError(msg: String) extends NoStackTrace:
    override def getMessage: String = msg
