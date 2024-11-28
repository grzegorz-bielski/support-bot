package supportbot
package integrations
package slack

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*

import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

import io.laserdisc.slack4s.slack.*
import io.laserdisc.slack4s.slashcmd.*

import supportbot.chat.*

import ChatService.*

// see: https://github.com/laserdisc-io/slack4s/blob/main/docs/tutorial.md
// use cloudflare tunnel instead of ngrok to expose localhost to the internet?

// TODO: rip out the slack4s and use the slack api directly

object SlackCommandMapperService:
  def of(using
    chatService: ChatService[IO],
    contextRepository: ContextRepository[IO],
  ): Resource[IO, CommandMapper[IO]] =
    for given Logger[IO] <- Slf4jLogger.create[IO].toResource
    yield payload =>
      val payloadText = payload.getText.trim // TODO: sanitize it
      val parts       = payloadText.split(" ").toList

      parts match
        case Nil =>
          Command(
            responseType = Immediate,
            handler = slackMessage(
              textSection(s"I'm sorry, I didn't understand that. Your query was ${payloadText}"),
            ).pure[IO],
          ).pure[IO]

        case head :: tail =>
          val contextName = head
          val userQuery   = tail.mkString(" ")

          Command(
            responseType = DelayedWithMsg(
              slackMessage(
                textSection(s"Querying the ${contextName} context with ${userQuery}..."),
              ),
            ),
            handler = for
              context  <- contextRepository.getByName(contextName)
              _        <- info"Found context(s): ${context}. Taking the first one, if any"
              response <- context.headOption match
                            case Some(contextInfo) =>
                              for
                                queryId      <- QueryId.of
                                chatInput     = contextInfo.toChatInput(
                                                  queryId = queryId,
                                                  query = ChatQuery(userQuery),
                                                )
                                chatResponse <- chatService.ask(chatInput)
                              yield slackMessage(textSection(chatResponse))

                            case None =>
                              slackMessage(textSection(s"I'm sorry, I couldn't find any context for $contextName"))
                                .pure[IO]
            yield response,
          ).pure[IO]

      // contextName.

      // println(s"Received a query from Slack: $userQuery")

      // Command(
      //     responseType =
      //         DelayedWithMsg(
      //             slackMessage(headerSection("Thinking..."))
      //         ),

      //     handler =
      //         IO.pure(slackMessage(textSection(s"echo: ${payload}"))),
      // ).pure[IO]
