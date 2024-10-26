package supportbot
package home

import org.http4s.{scalatags as _, h2 as _, *}
import cats.effect.*
import cats.syntax.all.*
import scalatags.Text.all.*
import scalatags.Text.TypedTag
import org.http4s.headers.Location
import org.http4s.implicits.*

object HomeController extends TopLevelHtmxController:
  def prefix = "/"
  def routes = IO:
    HttpRoutes.of[IO]:
      case GET -> Root =>
        Response[IO]()
          .withStatus(Status.Found)
          .withHeaders(Location(uri"/contexts"))
          .pure[IO]

object HomeView extends HtmxView:
  def view() =
    RootLayoutView.view(
      hero(),
    )

  def hero() =
    div(
      cls := "hero bg-base-200 h-[calc(100vh-6rem)]",
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
