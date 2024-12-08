package supportbot
package integrations
package slack

enum SlackAction:
  case WebHookResponse(
    responseUrl: String,
    payload: MsgPayload,
  )
