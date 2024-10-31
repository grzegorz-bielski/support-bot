package supportbot
package clickhouse

import cats.effect.*
import cats.derived.*
import cats.kernel.*
import cats.effect.kernel.Resource
import cats.syntax.all.*
import fs2.{Pipe, Stream, Chunk, text}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import scala.util.Try
import scala.util.control.NoStackTrace
import scala.concurrent.duration.*
import sttp.model.Uri
import ClickHouseClient.*

trait ClickHouseClient[F[_]]:
  def streamQueryJson[T](query: String)(using qs: QuerySettings, codec: JsonValueCodec[T]): Stream[F, T]
  def streamQueryTextLines(query: String)(using QuerySettings): Stream[F, String]
  def executeQuery(query: String)(using QuerySettings): F[Unit]

object ClickHouseClient:
  // uses 0 | 1 deliberately to match the CH settings conventions
  type IntBool = 0 | 1
  given Semigroup[IntBool] =
    Semigroup.instance((a, b) => (a | b).asInstanceOf[IntBool])

  given QuerySettings = QuerySettings.default

  // this uses snake_case deliberately to match the CH setting names 1:1
  final case class QuerySettings(
    wait_end_of_query: Option[IntBool] = None,
    output_format_json_quote_64bit_integers: Option[IntBool] = Some(0),
    allow_experimental_usearch_index: Option[IntBool] = Some(1),
    enable_http_compression: Option[IntBool] = Some(1),
    allow_experimental_inverted_index: Option[IntBool] = Some(1),
  ) derives Monoid:
    def asMap: Map[String, String] =
      productIterator.zipWithIndex
        .collect:
          case (Some(value), idx) => productElementName(idx) -> value.toString
        .toMap

  object QuerySettings:
    lazy val default: QuerySettings = QuerySettings()

  final case class Config(
    url: String,
    username: String,
    password: String,
    httpReadTimeout: Duration = 10.seconds,
  )

  sealed trait Error extends NoStackTrace
  object Error:
    final case class QueryFailed(details: String) extends Error:
      override def getMessage: String = details

    final case class ParsingFailed(reason: String, line: String) extends Error:
      override def getMessage: String = s"Parsing failed with reason [$reason]. Raw line [$line]"

final class SttpClickHouseClient(config: ClickHouseClient.Config)(using backend: SttpBackend)
    extends ClickHouseClient[IO]:

  def executeQuery(query: String)(using QuerySettings): IO[Unit] =
    requestOf(query)
      .send(backend)
      .map(_.body.leftMap(ClickHouseClient.Error.QueryFailed.apply))
      .rethrow
      .void

  def streamQueryJson[T](query: String)(using qs: QuerySettings, codec: JsonValueCodec[T]): Stream[IO, T] =
    streamQueryTextLines(query).through:
      _.collect:
        case line if line.nonEmpty =>
          Try(readFromString(line)).toEither
            .leftMap(error => ClickHouseClient.Error.ParsingFailed(error.getMessage, line))
      .rethrow

  def streamQueryTextLines(query: String)(using QuerySettings): Stream[IO, String] =
    Stream
      .eval:
        requestOf(query).response(asStreamUnsafe(Fs2Streams[IO])).send(backend)
      .map(_.body.leftMap(ClickHouseClient.Error.QueryFailed.apply))
      .rethrow
      .flatten
      .through(text.utf8.decode)
      .through(text.lines)

  private def requestOf(query: String)(using settings: QuerySettings) =
    val url =
      uri"${config.url}".addParams(settings.asMap)

    basicRequest
      .post(url)
      .auth
      .basic(
        user = config.username,
        password = config.password,
      )
      .body(query)
      .readTimeout(config.httpReadTimeout)

object SttpClickHouseClient:
  def of(using AppConfig, SttpBackend) =
    val chConf = AppConfig.get.clickhouse 

    SttpClickHouseClient(
      config = ClickHouseClient.Config(
        url = chConf.url,
        username = chConf.username,
        password = chConf.password,
      ),
    )
