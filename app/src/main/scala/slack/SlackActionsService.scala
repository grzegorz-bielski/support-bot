package supportbot
package integrations
package slack

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*

import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

import supportbot.chat.*

import ChatService.*

// see: https://github.com/laserdisc-io/slack4s/blob/main/docs/tutorial.md

type Actions[F[_]] = Vector[F[SlackAction]]

trait SlackActionsService[F[_]]:
  def processSlashCommand(payload: SlashCommandPayload): F[Actions[F]]

object SlackActionsService:
  def of(using ChatService[IO], ContextRepository[IO]): IO[SlackActionsService[IO]] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield SlackActionsServiceImpl()

final class SlackActionsServiceImpl(using
  chatService: ChatService[IO],
  contextRepository: ContextRepository[IO],
  logger: Logger[IO],
) extends SlackActionsService[IO]:
  def processSlashCommand(payload: SlashCommandPayload): IO[Actions[IO]] =
    val payloadText = payload.text.trim // TODO: sanitize it
    val parts       = payloadText.split(" ").toList

    parts match
      case Nil =>
        errorResponse(payload.responseUrl, s"I'm sorry, I didn't understand that. Your query was `${payloadText}`")
          .pure[IO]

      case head :: tail =>
        val contextName = head
        val userQuery   = tail.mkString(" ")

        // any Slack response to the webhook could be send up to 5 times within 30 minutes
        for
          context <- contextRepository.getByName(contextName)
          _       <- info"Found context(s): ${context}. Taking the first one, if any"
          actions  = context.headOption match
                       case Some(contextInfo) =>
                         processQuery(payload.responseUrl, userQuery, contextInfo)
                       case None              =>
                         errorResponse(
                           payload.responseUrl,
                           s"I'm sorry, I couldn't find any context for $contextName. Your query was `${payloadText}`",
                         )
        yield actions

  private def processQuery(responseUrl: String, userQuery: String, contextInfo: ContextInfo): Actions[IO] =
    Vector(
      SlackAction
        .WebHookResponse(
          responseUrl = responseUrl,
          payload = MsgPayload.fromBlocks(
            Block.Section(
              Block.Text.Plain(s"Querying the ${contextInfo.name} context with ${userQuery}..."),
            ),
          ),
        )
        .pure[IO],
      for
        queryId      <- QueryId.of
        chatResponse <- chatService.ask(
                          contextInfo.toChatInput(queryId = queryId, query = ChatQuery(userQuery)),
                        )
      yield SlackAction.WebHookResponse(
        responseUrl = responseUrl,
        payload = MsgPayload.fromBlocks(
          Block.Section(
            Block.Text.Markdown(chatResponse),
          ),
        ),
      ),
    )

  private def errorResponse(responseUrl: String, text: String): Actions[IO] =
    Vector(
      SlackAction
        .WebHookResponse(
          responseUrl = responseUrl,
          payload = MsgPayload.fromBlocks(
            Block.Section(
              Block.Text.Plain(text),
            ),
          ),
        )
        .pure[IO],
    )
