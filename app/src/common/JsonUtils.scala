package supportbot
package common

import com.github.plokhotnyuk.jsoniter_scala.core.*
import scala.util.Try

extension [T](underlying: T)
  def asJson(indentStep: Int = 0)(using codec: JsonValueCodec[T]): Either[String, String] =
    Try(writeToString(underlying, WriterConfig.withIndentionStep(indentStep))).toEither.left.map(_.getMessage)

extension (underlying: String)
  def unsafeParseToJson[T](using codec: JsonValueCodec[T]): T =
    readFromString[T](underlying)

  def parseToJson[T](using codec: JsonValueCodec[T]): Either[String, T] =
    Try(readFromString[T](underlying)).toEither.left.map(_.getMessage)