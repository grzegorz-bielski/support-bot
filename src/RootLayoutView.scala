package supportbot

import scalatags.Text.all.*
import scalatags.Text.tags2.title as headTitle

object RootLayoutView extends HtmxView:
  def view(children: scalatags.Text.Modifier*) = doctype("html")(
    html(
      data("theme") := "cupcake",
      lang          := "en",
      head(
        headTitle("atelier-confect"),
        meta(charset  := "UTF-8"),
        meta(
          name        := "viewport",
          content     := "width=device-width, initial-scale=1.0",
        ),
        // tailwind-generated styles
        link(
          rel         := "stylesheet",
          href        := s"/static/generated.css",
        ),
        script(
          src         := "https://unpkg.com/htmx.org@2.0.1",
          integrity   := "sha384-QWGpdj554B4ETpJJC9z+ZHJcA/i59TyjxEPXiiUgN2WmTyV5OEZWCD6gQhgkdpB/",
          crossorigin := "anonymous",
        ),
      ),
      body(
        cls := "px-4 py-2 m-auto max-w-screen-lg",
      )(
        menu,
        children,
      ),
    ),
  )

  lazy val menu = ul(
    cls := "menu menu-vertical lg:menu-horizontal bg-base-200 rounded-box",
  )(
    li(appLink("/", "Home")),
    li(appLink("/chat", "Chat")),
  )
