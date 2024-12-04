package supportbot
package integrations
package slack

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import eu.timepit.refined.types.string.NonEmptyString
import io.laserdisc.slack4s.slashcmd.*
import io.laserdisc.slack4s.slashcmd.internal.SignatureValidator
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

  protected def routes: IO[HttpRoutes[IO]] = IO:
    // TODO: replace with own implementation
    SignatureValidator
      .withValidSignature[IO](NonEmptyString.unsafeFrom(signingSecret))
      .apply:
        AuthedRoutes.of[SlackUser, IO]:
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
