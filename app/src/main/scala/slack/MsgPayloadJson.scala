package supportbot
package integrations
package slack

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import unindent.*

import supportbot.common.*

given JsonEncoder[Block.Text] =
  case Block.Text.Markdown(text) => i"""{"type": "mrkdwn", "text": "${text}"}"""
  case Block.Text.Plain(text)    => i"""{"type": "plain_text", "text": "${text}", emoji: true}"""

given JsonEncoder[Block.Section] = value =>
  i"""{"type": "section", "text": ${summon[JsonEncoder[Block.Text]].encode(value.text)}}"""

given JsonEncoder[Block] =
  case b: Block.Section => summon[JsonEncoder[Block.Section]].encode(b)
  case b: Block.Text    => summon[JsonEncoder[Block.Text]].encode(b)

given JsonValueCodec[Block] = 
  UnsafeJsonValueCodec.fromEncoder(empty = Block.Text.Plain(""))

given JsonValueCodec[MsgPayload] = JsonCodecMaker.make
