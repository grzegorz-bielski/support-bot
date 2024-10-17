package supportbot

import scalatags.Text.all.*
import scalatags.Text.tags2.{title as headTitle, details, summary}

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
          src         := "https://unpkg.com/htmx-ext-sse@2.2.2/sse.js",
        ),
      ),
      body(
        cls := "px-4 py-2 m-auto max-w-screen-2xl",
      )(
        navbar,
        children,
      ),
    ),
  )

  lazy val navbar =
    div(
      cls := "navbar bg-base-100",
      div(cls := "flex-1", appLink("/", "SupportBot", cls := "btn btn-ghost text-xl")),
      div(
        cls   := "flex-none",
        ul(
          cls := "menu menu-horizontal px-1",
          li(
            details(
              summary("Context"),
              ul(
                cls := "bg-base-100 rounded-t-none p-2",
                li(
                  appLink("/chat", "Safe3"),
                ),
              ),
            ),
          ),
        ),
      ),
    )
