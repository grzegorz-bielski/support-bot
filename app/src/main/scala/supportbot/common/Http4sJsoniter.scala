package supportbot
package common

import com.github.plokhotnyuk.jsoniter_scala.core.*
import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.http4s.headers.*

// borrowed from https://github.com/cornerman/http4s-jsoniter

given [F[_]: Concurrent, A: JsonValueCodec] => EntityDecoder[F, A] =
  EntityDecoder.decodeBy(MediaType.application.json):
    EntityDecoder
      .byteArrayDecoder[F]
      .flatMapR: bytes =>
        DecodeResult(
          Concurrent[F].pure(
            Either
              .catchNonFatal(readFromArray(bytes))
              .leftMap(decodeResultFailure),
          ),
        )
      .decode(_, strict = true)

given [F[_], A: JsonValueCodec] => EntityEncoder[F, A] =
  EntityEncoder.encodeBy[F, A](Headers(`Content-Type`(MediaType.application.json))):
    EntityEncoder
      .byteArrayEncoder[F]
      .contramap[A](writeToArray(_))
      .toEntity(_)

private def decodeResultFailure(error: Throwable): DecodeFailure =
  MalformedMessageBodyFailure("Invalid JSON", Some(error))
