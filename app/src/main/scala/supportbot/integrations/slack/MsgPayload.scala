package supportbot
package integrations
package slack

/** Slack message payload
  *
  * @see
  *   https://api.slack.com/surfaces/messages#payloads
  */
final case class MsgPayload(
  text: String,
  blocks: Vector[Block],
)

object MsgPayload:
  def fromBlocks(text: String, blocks: Block*) =
    MsgPayload(
      text = text,
      blocks = blocks.toVector,
    )

/** Slack Block Kit element
  *
  * @see
  *   https://api.slack.com/block-kit
  */
sealed trait Block
object Block:
  sealed trait Text extends Block:
    def text: String

  object Text:
    final case class Markdown(text: String)  extends Text
    final case class Plain(text: String) extends Text

  final case class Section(text: Text) extends Block
