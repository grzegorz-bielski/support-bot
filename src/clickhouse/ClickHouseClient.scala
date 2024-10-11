package supportbot
package clickhouse

import cats.effect.*
import cats.effect.kernel.Resource
import cats.syntax.all.*
import fs2.{Pipe, Stream, Chunk, text}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.log4cats.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import scala.util.Try
import scala.util.control.NoStackTrace
import scala.concurrent.duration.*
import sttp.model.Uri
import ClickHouseClient.*

trait ClickHouseClient[F[_]]:
  def streamQuery[T: JsonValueCodec](query: String): Stream[F, T]
  def executeQuery(query: String): F[Unit]

object ClickHouseClient:
  final case class Config(
      url: String,
      username: String,
      password: String,
      httpReadTimeout: Duration = 10.seconds
  )

  sealed trait Error extends NoStackTrace derives CanEqual
  object Error:
    final case class QueryFailed(details: String) extends Error:
      override def getMessage: String = details

    final case class ParsingFailed(reason: String, line: String) extends Error:
      override def getMessage: String = s"Parsing failed with reason [$reason]. Raw line [$line]"

final class SttpClickHouseClient(config: ClickHouseClient.Config)(using backend: SttpBackend)
    extends ClickHouseClient[IO]:

  def executeQuery(query: String): IO[Unit] =
    requestOf(query)
      .send(backend)
      .map(_.body.leftMap(ClickHouseClient.Error.QueryFailed.apply))
      .rethrow
      .void

  def streamQuery[T: JsonValueCodec](query: String): Stream[IO, T] =
    Stream
      .eval(
        requestOf(query).response(asStreamUnsafe(Fs2Streams[IO])).send(backend)
      )
      .map(_.body.leftMap(ClickHouseClient.Error.QueryFailed.apply))
      .rethrow
      .flatten
      .through(deserialize)

  private def deserialize[T: JsonValueCodec]: Pipe[IO, Byte, T] =
    _.through(text.utf8.decode)
      .through(text.lines)
      .through:
        _.collect:
          case line if line.nonEmpty =>
            Try(readFromString(line)).toEither
              .leftMap(error => ClickHouseClient.Error.ParsingFailed(error.getMessage, line))
        .rethrow

  private def requestOf(query: String) =
    basicRequest
      .post(
        uri"${config.url}"
          .addParam("output_format_json_quote_64bit_integers", "0")
          .addParam("allow_experimental_usearch_index", "1")
          // reduce network traffic when transmitting a large amount of data:
          // https://clickhouse.com/docs/en/operations/settings/settings#enable_http_compression
          .addParam("enable_http_compression", "1")
      )
      .auth
      .basic(
        user = config.username,
        password = config.password
      )
      .body(query)
      .readTimeout(config.httpReadTimeout)
