package supportbot

import scalatags.Text.all.*
import scalatags.Text.tags2.{title as headTitle, details, summary}

object RootLayoutView extends HtmxView:
  def view(children: scalatags.Text.Modifier*)(using AppConfig) =
    val headContent =
      Vector(
        headTitle("supportbot"),
        meta(charset  := "UTF-8"),
        meta(
          name        := "viewport",
          content     := "width=device-width, initial-scale=1.0",
        ),
        // tailwind-generated styles
        link(
          rel         := "stylesheet",
          href        := "/static/generated.css",
        ),
        script(
          src := "/static/bundle.js",
          defer
        ),
      ) ++ devSetup

    doctype("html")(
      html(
        data("theme") := "cmyk",
        lang          := "en",
        head(headContent*),
        body(
          cls := "px-4 py-2 min-h-screen mx-auto max-w-screen-2xl",
        )(
          navbar,
          children,
        ),
      ),
    )

  private def devSetup(using appConfig: AppConfig): Vector[Modifier] =
    if appConfig.isDev then
      // this is so we can add new tailwind classes directly in the browser during development
      Vector(
        link(
          rel  := "stylesheet",
          href := "https://cdn.jsdelivr.net/npm/daisyui@4.12.14/dist/full.min.css",
        ),
        script(
          src  := "https://cdn.tailwindcss.com",
        ),
      )
    else Vector.empty

  lazy val navbar =
    div(
      cls := "navbar bg-base-100",
      div(cls := "flex-1", appLink("/", "SupportBot 🤖", cls := "btn btn-ghost text-xl")),
    )
