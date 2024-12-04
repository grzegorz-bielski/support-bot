package supportbot
package integrations
package slack

enum SlackAction:
  case WebHookResponse(
    responseUrl: String,
    payload: MsgPayload,
  )

extension [F[_]](underlying: F[SlackAction])
  def single: Vector[F[SlackAction]] = Vector(underlying)
