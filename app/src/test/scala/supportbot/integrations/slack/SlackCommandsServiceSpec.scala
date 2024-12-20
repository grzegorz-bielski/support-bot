package supportbot
package integrations
package slack

import fs2.*
import cats.effect.*
import cats.effect.std.*
import java.util.UUID
import cats.syntax.all.*
import unindent.*
import munit.*
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.*
import scala.concurrent.duration.*

import supportbot.chat.*
import supportbot.chat.ChatService.Input

import SlackCommandsServiceImpl.*

class SlackCommandsServiceSpec extends CatsEffectSuite:
  val testUUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

  test("should schedule and execute slack actions"):
    for
      sentMessages    <- Ref.of[IO, Vector[MsgPayload]](Vector.empty)
      sentChatQueries <- Ref.of[IO, Vector[ChatService.Input]](Vector.empty)
      promise         <- Deferred[IO, Unit]

      commandsNumber = 20
      ctxName        = "ctx-name"

      given Logger[IO]      = NoOpLogger[IO]
      given SlackClient[IO] = new SlackClient[IO]:
                                def respondTo(responseUrl: String, response: MsgPayload): IO[Unit] =
                                  sentMessages
                                    .updateAndGet(_ :+ response)
                                    .flatMap: updated =>
                                      if updated.size == commandsNumber * 2 // 2 responses per command
                                      then promise.complete(()).void
                                      else IO.unit

      given ChatService[IO] = new ChatService[IO]:
                                def processQuery(input: Input): IO[Unit] =
                                  sentChatQueries.update(_ :+ input)

                                def subscribeToQueryResponses(queryId: QueryId): Stream[IO, ChatService.Response] =
                                    Stream(
                                      ChatService.Response.Partial(queryId, "res"),
                                      ChatService.Response.Partial(queryId, "ponse"),
                                      ChatService.Response.Finished(queryId),
                                    )

      given ContextRepository[IO] = new ContextRepository[IO]:
                                      def createOrUpdate(info: ContextInfo): IO[Unit]        = ???
                                      def getAll: IO[Vector[ContextInfo]]                    = ???
                                      def get(contextId: ContextId): IO[Option[ContextInfo]] = ???
                                      def getByName(name: String): IO[Vector[ContextInfo]]   =
                                        IO(
                                          Vector(
                                            ContextInfo.default(
                                              id = ContextId(testUUID),
                                              name = ctxName,
                                              description = "test",
                                            ),
                                          ),
                                        )
                                      def delete(id: ContextId): IO[Unit]                    = ???

      commandsToProcess = Vector.tabulate(commandsNumber): n =>
                            SlashCommandPayload(
                              apiAppId = "test",
                              teamId = "test",
                              teamDomain = "test",
                              enterpriseId = none,
                              enterpriseName = none,
                              channelId = "test",
                              channelName = "test",
                              userId = s"test-$n",
                              command = "test",
                              text = s"$ctxName hello $n",
                              responseUrl = "test",
                              triggerId = "test",
                              isEnterpriseInstall = false,
                            )

      _ <- SlackCommandsService
             .of(maxConcurrentSessions = 10, maxSessions = 100)
             .use: cmdService =>
               commandsToProcess.parTraverse_(cmdService.process) *> promise.get

      _ <- sentMessages.get.map(_.size) assertEquals commandsNumber * 2 // 2 responses per command
      
      expectedQueries = commandsToProcess.map(_.text.drop(ctxName.length + 1))
      _ <- sentChatQueries.get.map(_.size) assertEquals commandsNumber
      _ <- sentChatQueries.get.map(_.map(_.query.content).toSet) assertEquals expectedQueries.toSet
    yield ()
