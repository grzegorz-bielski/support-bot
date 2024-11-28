package supportbot
package integrations
package slack

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import eu.timepit.refined.types.string.NonEmptyString
import io.laserdisc.slack4s.slashcmd.*
import io.laserdisc.slack4s.slack.internal.SlackAPIClient
import io.laserdisc.slack4s.slashcmd.internal.SignatureValidator
import io.laserdisc.slack4s.slashcmd.internal.CommandRunner
import org.http4s.*
import org.typelevel.log4cats.Logger

final class SlackBotController(
  slackClient: SlackAPIClient[IO],
  cmdRunner: CommandRunner[IO],
  signingSecret: String,
)(using logger: Logger[IO])
    extends TopLevelController:
  protected def prefix: String = "slack"

  protected def routes: IO[HttpRoutes[IO]] = IO:
    SignatureValidator
      .withValidSignature[IO](NonEmptyString.unsafeFrom(signingSecret))
      .apply:
        AuthedRoutes.of[SlackUser, IO]:
          case req @ POST -> Root / "slashCmd" as _ => cmdRunner.processRequest(req)

object SlackBotController:
  def of(commandMapper: CommandMapper[IO], signingSecret: String)(using
    Logger[IO],
  ): Resource[IO, SlackBotController] =
    for
      slackClient <- SlackAPIClient.resource[IO]
      cmdRunner   <- CommandRunner[IO](slackClient, commandMapper).toResource
      _           <- cmdRunner.processBGCommandQueue.compile.drain.background
    yield SlackBotController(slackClient, cmdRunner, signingSecret)
