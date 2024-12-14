package supportbot
package integrations
package slack

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import org.http4s.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

import supportbot.common.{*, given}

final class SlackBotController(
  signingSecret: String,
)(using logger: Logger[IO], slackCommandsService: SlackCommandsService[IO])
    extends TopLevelController:
  protected def prefix: String = "slack"

  private lazy val authed =
    SlackSignatureVerifier.middleware[IO](signingSecret)

  protected def routes: IO[HttpRoutes[IO]] = IO:
    authed:
      AuthedRoutes.of[AuthInfo, IO]:
        case arReg @ POST -> Root / "slashCmd" as _ =>
          given EntityDecoder[IO, SlashCommandPayload] = FormDataDecoder.formEntityDecoder

          for
            cmd <- arReg.req.as[SlashCommandPayload]
            _   <- slackCommandsService.process(cmd)
            res <- Ok()
          yield res

object SlackBotController:
  def of(using
    AppConfig,
    Logger[IO],
    SttpBackend,
    SlackCommandsService[IO],
  ): Resource[IO, SlackBotController] =
    for given Logger[IO] <- Slf4jLogger.create[IO].toResource
    yield SlackBotController(signingSecret = AppConfig.get.slack.signingSecret)
