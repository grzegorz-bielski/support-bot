package supportbot
package integrations
package slack

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import org.http4s.*
import org.typelevel.log4cats.Logger

import supportbot.common.{*, given}

final class SlackBotController(
  actionsService: SlackActionsService[IO],
  actionsExecutor: SlackActionsExecutor[IO],
  signingSecret: String,
)(using logger: Logger[IO])
    extends TopLevelController:
  protected def prefix: String = "slack"

  private lazy val authed =
    SlackSignatureVerifier.middleware[IO](signingSecret)

  protected def routes: IO[HttpRoutes[IO]] = IO:
    authed:
      AuthedRoutes.of[AuthInfo, IO]:
        case arReg @ POST -> Root / "slashCmd" as _ =>
          for
            payload <- arReg.req.as[SlashCommandPayload]
            actions <- actionsService.processSlashCommand(payload)
            _       <- actions.map(actionsExecutor.schedule).sequence.void
            res     <- Ok()
          yield res

object SlackBotController:
  def of(actionsService: SlackActionsService[IO], signingSecret: String)(using
    Logger[IO],
    SttpBackend,
  ): Resource[IO, SlackBotController] =
    for
      actionsExecutor <- SlackActionsExecutor.of.toResource
      _               <- actionsExecutor.stream.compile.drain.background
    yield SlackBotController(actionsService, actionsExecutor, signingSecret)
