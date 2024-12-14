package supportbot
package integrations
package slack

import fs2.*
import cats.effect.*
import cats.effect.std.Queue
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
    given Logger[IO]      = NoOpLogger[IO]
    given SlackClient[IO] = new SlackClient[IO]:
                                def respondTo(responseUrl: String, response: MsgPayload): IO[Unit] =
                                  IO.println(s"Responding to $responseUrl with $response") *>
                                    IO.sleep(5.second)
                                
    given ChatService[IO] = new ChatService[IO]:
                                def processQuery(input: Input): IO[Unit] = IO.unit
                                def subscribeToQueryResponses(queryId: QueryId): Stream[IO, ChatService.Response] = Stream.empty

    SlackCommandsService
      .of(maxConcurrentSessions = 10, maxSessions = 100)
      .flatMap: cmdService =>
          cmdService.process(
            SlackSlashCommandPayload(
              apiAppId = "test",
              teamId = "test",
              teamDomain = "test",
              enterpriseId = none,
              enterpriseName = none,
              channelId = "test",
              channelName = "test",
              userId = "test",
              command = "test",
              text = "test",
              responseUrl = "test",
              triggerId = "test",
              isEnterpriseInstall = false,
            )
          )

    // for
    //   // queue                <- Queue.unbounded[IO, SlackActionEnvelope[IO]]
    //   // given Logger[IO]      = NoOpLogger[IO]
    //   // given SlackClient[IO] = new SlackClient[IO]:
    //   //                           def respondTo(responseUrl: String, response: MsgPayload): IO[Unit] =
    //   //                             IO.println(s"Responding to $responseUrl with $response") *>
    //   //                               IO.sleep(5.second) *>
    //   //                               IO.unit
                                
    //   // given ChatService[IO] = new ChatService[IO]:
    //   //                           def processQuery(input: Input): IO[Unit] = IO.unit
    //   //                           def subscribeToQueryResponses(queryId: QueryId): Stream[IO, ChatService.Response] = Stream.empty

    //   cmdService 
    //     <- SlackCommandsService.of(maxConcurrentSessions = 10, maxSessions = 100)
    //   // fiber               <- slackActionsExecutor.stream.compile.drain.start

    //   // _ <- Vector
    //   //        .tabulate(4): n =>
    //   //          SlackActionEnvelope(
    //   //            queryId = QueryId(testUUID),
    //   //            sessionId = SessionId(testUUID),
    //   //            action = SlackAction
    //   //              .WebHookResponse("http://localhost", MsgPayload.fromBlocks(Block.Text.Plain(s"test-$n")))
    //   //              .pure[IO],
    //   //          )
    //   //        .traverse_(slackActionsExecutor.schedule)

    //   // _ <- fiber.join
    //   // _ <- IO.sleep(20.second).toResource
    //   // _ <- IO.println("Cancelling fiber").toResource
    //   // _ <- fiber.cancel.to

    // // _ <- slackActionsExecutor.schedule(IO.pure(action))
    // yield ()
    // val queue = Queue.unbounded[IO, IO[SlackAction]].unsafeRunSync()
    // val executor = SlackActionsExecutorImpl(queue)

    // val action = SlackAction.WebHookResponse("http://localhost", "test")
    // executor.schedule(IO.pure(action)).unsafeRunSync()

    // val stream = executor.stream.compile.toList.unsafeRunSync()
    // assertEquals(stream, List(()))
  // }
