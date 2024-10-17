package supportbot

import scalatags.Text.all.*
import scalatags.Text.tags2.title as headTitle

object RootLayoutView extends HtmxView:
  def view(children: scalatags.Text.Modifier*) = doctype("html")(
    html(
      data("theme") := "cupcake",
      lang          := "en",
      head(
        headTitle("supportbot"),
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
          src         := "https://unpkg.com/htmx.org@2.0.3",
          integrity   := "sha384-0895/pl2MU10Hqc6jd4RvrthNlDiE9U1tWmX7WRESftEDRosgxNsQG/Ze9YMRzHq",
          crossorigin := "anonymous",
        ),
        script(
          src := "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js"
        )
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
