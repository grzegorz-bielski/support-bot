package supportbot
package integrations
package slack

import fs2.*
import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import cats.effect.syntax.all.*

import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

trait SlackActionsExecutor[F[_]]:
  def schedule(action: F[SlackAction]): F[Unit]
  def stream: Stream[F, Unit]

object SlackActionsExecutor:
  def of(using SttpBackend): IO[SlackActionsExecutor[IO]] =
    for
      given Logger[IO]     <- Slf4jLogger.create[IO]
      given SlackClient[IO] = SttpSlackClient()
      queue                <- Queue.unbounded[IO, IO[SlackAction]]
    yield SlackActionsExecutorImpl(queue)

final class SlackActionsExecutorImpl(queue: Queue[IO, IO[SlackAction]])(using
  client: SlackClient[IO],
  logger: Logger[IO],
) extends SlackActionsExecutor[IO]:
  def schedule(action: IO[SlackAction]): IO[Unit] =
    info"Scheduling action: $action" *> queue.offer(action)

  def stream: Stream[IO, Unit] =
    Stream
      .fromQueueUnterminated(queue)
      .evalMap:
      // TODO: action eval could be slow and block the queue
      // test it out with .parEvalMap(10):
        _.attempt.flatMap:
          case Left(err) =>
            logger.error(err)("Failed to execute action: eval failed")

          case Right(action) =>
            info"Executing action: $action" *>
              executeAction(action).handleErrorWith: err =>
                logger.error(err)("Failed to execute action: executor failed")

  private def executeAction(action: SlackAction): IO[Unit] =
    action match
      case SlackAction.WebHookResponse(responseUrl, payload) =>
        client.respondTo(responseUrl, payload)
