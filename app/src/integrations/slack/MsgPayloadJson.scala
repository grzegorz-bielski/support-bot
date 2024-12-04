package supportbot
package integrations
package slack

import java.nio.charset.StandardCharsets
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import unindent.*

trait JsonEncoder[T]:
  def encode(value: T): String

given JsonEncoder[Block.Text] =
  case Block.Text.Markdown(text) => i"""{"type": "mrkdwn", "text": "${text}"}"""
  case Block.Text.Plain(text)    => i"""{"type": "plain_text", "text": "${text}", emoji: true}"""

given JsonEncoder[Block.Section] = value =>
  i"""{"type": "section", "text": ${summon[JsonEncoder[Block.Text]].encode(value.text)}}"""

given JsonEncoder[Block] =
  case b: Block.Section => summon[JsonEncoder[Block.Section]].encode(b)

given JsonValueCodec[Block] = new JsonValueCodec[Block]:
  def encodeValue(x: Block, out: JsonWriter): Unit =
    out.writeRawVal(summon[JsonEncoder[Block]].encode(x).getBytes(StandardCharsets.UTF_8))

  def decodeValue(in: JsonReader, default: Block): Block =
    // jsoniter-scala won't let us to have only encoders or only decoders :shrug:
    // see: https://github.com/plokhotnyuk/jsoniter-scala/issues/111
    throw UnsupportedOperationException("Decoders for Block are not implemented (!)")

  def nullValue: Block = Block.Text.Plain("")

given JsonValueCodec[MsgPayload] = JsonCodecMaker.make
