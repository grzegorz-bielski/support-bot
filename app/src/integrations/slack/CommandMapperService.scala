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
import com.slack.api.app_backend.slash_commands.payload.SlashCommandPayload

import supportbot.chat.*

import ChatService.*

// see: https://github.com/laserdisc-io/slack4s/blob/main/docs/tutorial.md

// TODO: rip out the slack4s and use the slack api directly

trait CommandMapperService[F[_]]:
  def processSlashCommand(payload: SlashCommandPayload): F[Command[F]]

object CommandMapperService:
  def of(using ChatService[IO], ContextRepository[IO]): Resource[IO, CommandMapperService[IO]] =
    for given Logger[IO] <- Slf4jLogger.create[IO].toResource
    yield CommandMapperServiceImpl()

final class CommandMapperServiceImpl(using
  chatService: ChatService[IO],
  contextRepository: ContextRepository[IO],
  logger: Logger[IO],
) extends CommandMapperService[IO]:
  def processSlashCommand(payload: SlashCommandPayload): IO[Command[IO]] =
    val payloadText = payload.getText.trim // TODO: sanitize it
    val parts       = payloadText.split(" ").toList

    parts match
      case Nil =>
          Action.Immediate(
            slackMessage(
              textSection(s"I'm sorry, I didn't understand that. Your query was `${payloadText}`"),
            ).pure[IO],
          ).pure[IO]

      case head :: tail =>
        val contextName = head
        val userQuery   = tail.mkString(" ")

        // (!) anything in this top-level IO should happen within 3 seconds, otherwise slack will timeout the request
        for
          context <- contextRepository.getByName(contextName)
          _       <- info"Found context(s): ${context}. Taking the first one, if any"
        yield context.headOption match
          case Some(contextInfo) =>
              Action.Immediate(
                slackMessage(
                  textSection(s"Querying the ${contextInfo.name} context with ${userQuery}..."),
                ).pure[IO],
              ) ->
              Action.Delayed(
                for
                  queryId      <- QueryId.of
                  chatResponse <- chatService.ask(
                                    contextInfo.toChatInput(queryId = queryId, query = ChatQuery(userQuery)),
                                  )
                yield slackMessage(textSection(chatResponse)),
              )

          case None =>
              Action.Immediate(
                slackMessage(
                  textSection(
                    s"I'm sorry, I couldn't find any context for $contextName. Your query was `${payloadText}`",
                  ),
                ).pure[IO],
              )
