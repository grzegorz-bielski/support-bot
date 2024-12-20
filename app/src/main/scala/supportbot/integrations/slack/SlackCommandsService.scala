package supportbot
package integrations
package slack

import fs2.*
import cats.effect.*
import cats.syntax.all.*
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import scala.util.Try

import supportbot.chat.*

import ChatService.*
import SlackCommandsServiceImpl.*

trait SlackCommandsService[F[_]]:
  def process(cmd: SlackCommand): F[Unit]

object SlackCommandsService:
  def of(using AppConfig, SttpBackend, ChatService[IO], ContextRepository[IO]): Resource[IO, SlackCommandsService[IO]] =
    for
      given Logger[IO]     <- Slf4jLogger.create[IO].toResource
      given SlackClient[IO] = SttpSlackClient()
      slackConfig           = AppConfig.get.slack
      service              <- of(
                                maxSessions = slackConfig.maxSessions,
                                maxConcurrentSessions = slackConfig.maxConcurrentSessions,
                              )
    yield service

  def of(
    maxSessions: Int,
    maxConcurrentSessions: Int,
  )(using ChatService[IO], ContextRepository[IO], Logger[IO], SlackClient[IO]): Resource[IO, SlackCommandsService[IO]] =
    for
      given SlackActionsExecutor <- SlackActionsExecutor
                                      .of(
                                        maxSessions = maxSessions,
                                        maxConcurrentSessions = maxConcurrentSessions,
                                      )
                                      .toResource
      _                          <- summon[SlackActionsExecutor].stream.compile.drain.background
    yield SlackCommandsServiceImpl()

final case class UserQuery(context: String, query: String)

final class SlackCommandsServiceImpl(using
  chatService: ChatService[IO],
  contextRepository: ContextRepository[IO],
  logger: Logger[IO],
  slackClient: SlackClient[IO],
  executor: SlackActionsExecutor,
) extends SlackCommandsService[IO]:
  def process(cmd: SlackCommand): IO[Unit] =
    val slackEvents = cmd match
      case payload: SlashCommandPayload => processSlashCommand(payload)

    slackEvents
      .flatMap(executor.schedule)
      .handleErrorWith(logger.error(_)("Failed to process Slack command"))

  private def processSlashCommand(payload: SlashCommandPayload): IO[EventEnvelope] =
    for
      // current assumption = 1 session per query
      given SessionId <- SessionId.of
      given QueryId   <- QueryId.of

      parsedQuery = parseQuery(payload)
      envelope   <-
        parsedQuery match
          case None =>
            errorResponse(
              payload.responseUrl,
              s"I'm sorry, I didn't understand that. Your query was `${payload.text}`",
            ).pure[IO]

          case Some(UserQuery(contextName, userQuery)) =>
            // any Slack response to the webhook could be send up to 5 times within 30 minutes
            for
              context <- contextRepository.getByName(contextName)
              _       <- info"Found context(s): ${context}. Taking the first one, if any"
              action   = context.headOption match
                           case Some(contextInfo) =>
                             processQuery(payload.responseUrl, userQuery, contextInfo)
                           case None              =>
                             errorResponse(
                               payload.responseUrl,
                               s"I'm sorry, I couldn't find any context for $contextName. Your query was `${payload.text}`",
                             )
            yield action
    yield envelope

  private def parseQuery(payload: SlashCommandPayload): Option[UserQuery] =
    Try:
      val parts = payload.text.trim.split(" ").toList // TODO: sanitize it better

      parts match
        case head :: tail => UserQuery(context = head, query = tail.mkString(" ")).some
        case Nil          => none
    .toOption.flatten

  private def processQuery(
    responseUrl: String,
    userQuery: String,
    contextInfo: ContextInfo,
  )(using queryId: QueryId, sessionId: SessionId): EventEnvelope =
    EventEnvelope(
      queryId = queryId,
      sessionId = sessionId,
      handler = for
        _ <- slackClient.respondTo(
               responseUrl = responseUrl,
               response =
                 val text = s"Querying the ${contextInfo.name} context with `${userQuery}` ..."
                 MsgPayload.fromBlocks(
                   text = text,
                   blocks = Block.Section(
                     Block.Text.Plain(text),
                   ),
                 ),
             )

        chatInput     = contextInfo.toChatInput(queryId = queryId, query = ChatQuery(userQuery))
        chatResponse <- chatService.ask(chatInput)

        _ <- slackClient.respondTo(
               responseUrl = responseUrl,
               response = MsgPayload.fromBlocks(
                 text = chatResponse,
                 blocks = Block.Section(
                   Block.Text.Markdown(chatResponse),
                 ),
               ),
             )
      yield (),
    )

  private def errorResponse(responseUrl: String, text: String)(using
    queryId: QueryId,
    sessionId: SessionId,
  ): EventEnvelope =
    EventEnvelope(
      queryId = queryId,
      sessionId = sessionId,
      handler = slackClient.respondTo(
        responseUrl = responseUrl,
        response = MsgPayload.fromBlocks(
          text = text,
          blocks = Block.Section(
            Block.Text.Plain(text),
          ),
        ),
      ),
    )

object SlackCommandsServiceImpl:
  private[slack] final case class EventEnvelope(
    queryId: QueryId,
    sessionId: SessionId,
    handler: IO[Unit],
  )

  private[slack] final class SlackActionsExecutor(
    maxConcurrentSessions: Int,
    queue: Queue[IO, EventEnvelope],
  )(using logger: Logger[IO]):
    def schedule(envelope: EventEnvelope): IO[Unit] =
      info"Scheduling action: $envelope" *> queue.offer(envelope)

    def stream: Stream[IO, Unit] =
      Stream
        .fromQueueUnterminated(queue)
        // current assumption = 1 session per query
        .parEvalMap(maxConcurrentSessions):
          _.handler.attempt.flatMap:
            case Left(err)     => logger.error(err)("Failed to execute action: eval failed")
            case Right(action) => IO.unit

  private[slack] object SlackActionsExecutor:
    def of(maxSessions: Int, maxConcurrentSessions: Int)(using Logger[IO]): IO[SlackActionsExecutor] =
      for queue <- Queue.bounded[IO, EventEnvelope](maxSessions)
      yield SlackActionsExecutor(maxConcurrentSessions, queue)
