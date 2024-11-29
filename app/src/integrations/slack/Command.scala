package supportbot
package integrations
package slack

import com.slack.api.methods.request.chat.ChatPostMessageRequest

type Command[F[_]] =
  Action.Immediate[F] | Action.Delayed[F] | (Action.Immediate[F], Action.Delayed[F])

type ChatResponse = ChatPostMessageRequest

enum Action[F[_]]:
  case Immediate(handler: F[ChatResponse])
  case Delayed(handler: F[ChatResponse])
