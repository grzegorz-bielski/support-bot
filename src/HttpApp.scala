package supportbot

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.std.Env
import org.http4s.dsl.*
import org.http4s.{scalatags as _, *}
import org.http4s.scalatags.ScalatagsInstances
import org.http4s.server.*
import org.http4s.server.staticcontent.*
import org.http4s.ember.server.*
import com.comcast.ip4s.*
import scalatags.Text.all.*
import scalatags.Text.TypedTag
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

def httpApp(
  host: Host = ipv4"0.0.0.0",
  port: Port = port"8080",
  controllers: Controller*,
): Resource[IO, Server] =
  val app = for
    given Logger[IO] <- Slf4jLogger.create[IO]
    _                <- info"Starting the server at $host:$port"
    mappings         <- controllers
                          .appended(StaticAssetsController())
                          .appended(HomePageController)
                          .traverse(_.mapping)
  yield Router(mappings*).orNotFound

  app.toResource.flatMap:
    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(_)
      .build

object HomePageController extends HtmxController:
  def prefix = "/"
  def routes = IO:
    HttpRoutes.of[IO]:
      case GET -> Root =>
        Ok(
          RootLayoutView.view(
            HomePageView.hero(),
          ),
        )

object HomePageView extends HtmxView:
  def hero() =
    div(
      cls := "hero bg-base-200 min-h-screen",
      div(
        cls := "hero-content text-center",
        div(
          cls := "max-w-md",
          h1(cls := "text-8xl font-bold text-secondary skew-y-3", "Hello!"),
          div(
            cls  := "py-9",
            p(
              cls := "py-2",
              "SupportBot is your team's private assistant that can help you automate mundane tasks.",
            ),
            p(
              cls := "py-2",
              "Bring in your own documents and enterprise data from Jira or Confluence. Use the bot through web UI or integrate it with Slack. Easy to deploy and manage.",
            ),
            p(
              cls := "py-2",
              "All your data stays private and it's not send to any third-parties like OpenAI.",
            ),
          ),
        ),
      ),
    )

final class StaticAssetsController(using Logger[IO]) extends Controller:
  def prefix = "static"
  def routes = Env[IO]
    .get("ENV")
    .flatMap:
      case Some("local") =>
        warn"Using local resources".as(fileService[IO](FileService.Config("./resources/static")))
      case _             =>
        // assuming production env - we use the resources embedded in the JAR
        info"Using embedded resources".as(resourceServiceBuilder[IO]("/static").toRoutes)
