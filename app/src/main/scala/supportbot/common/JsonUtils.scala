package supportbot
package common

import com.github.plokhotnyuk.jsoniter_scala.core.*
import java.nio.charset.StandardCharsets
import scala.util.Try

extension [T](underlying: T)
  def asJson(indentStep: Int = 0)(using codec: JsonValueCodec[T]): Either[String, String] =
    Try(writeToString(underlying, WriterConfig.withIndentionStep(indentStep))).toEither.left.map(_.getMessage)

extension (underlying: String)
  def unsafeParseToJson[T](using codec: JsonValueCodec[T]): T =
    readFromString[T](underlying)

  def parseToJson[T](using codec: JsonValueCodec[T]): Either[String, T] =
    Try(readFromString[T](underlying)).toEither.left.map(_.getMessage)

trait JsonEncoder[T]:
  def encode(value: T): String

/*
  * This is a workaround for the issue with jsoniter-scala not allowing to have only encoders or only decoders.
  * See: https://github.com/plokhotnyuk/jsoniter-scala/issues/111
  * 
  * (!) It will throw in runtime for missing implementations. (!)
*/
object UnsafeJsonValueCodec:
  def fromEncoder[T: JsonEncoder](empty: T): JsonValueCodec[T] = new JsonValueCodec[T]:
    def encodeValue(x: T, out: JsonWriter): Unit =
      out.writeRawVal(summon[JsonEncoder[T]].encode(x).getBytes(StandardCharsets.UTF_8))

    def decodeValue(in: JsonReader, default: T): T =
      throw UnsupportedOperationException("Decoders are not implemented (!)")

    def nullValue: T = empty
