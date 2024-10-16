package supportbot

import cats.effect.*
import cats.syntax.all.*
import org.http4s.dsl.*
import org.http4s.{scalatags as _, *}
import org.http4s.scalatags.ScalatagsInstances
import scalatags.Text.all.*
import scalatags.Text.TypedTag

trait Controller extends Http4sDsl[IO]:
  protected def prefix: String
  protected def routes: IO[HttpRoutes[IO]]

  final def mapping: IO[(String, HttpRoutes[IO])] =
    routes.map(prefix -> _)

trait HtmxController extends Controller, ScalatagsInstances, HtmxView

trait HtmxView:
  opaque type HtmxAttr <: Attr = Attr

  lazy val `hx-post`: HtmxAttr     = attr("hx-post")
  lazy val `hx-swap`: HtmxAttr     = attr("hx-swap")
  lazy val `hx-target`: HtmxAttr   = attr("hx-target")
  lazy val `hx-on:click`: HtmxAttr = attr("hx-on:click")
  lazy val `hx-delete`: HtmxAttr   = attr("hx-delete")
  lazy val `hx-boost`: HtmxAttr    = attr("hx-boost")
  lazy val `hx-ext`: HtmxAttr      = attr("hx-ext")
  lazy val `sse-connect`: HtmxAttr = attr("sse-connect")
  lazy val `sse-swap`: HtmxAttr    = attr("sse-swap")

  def appLink(path: String, text: String): TypedTag[String] =
    a(
      attr("href") := path,
      `hx-boost`   := "true",
    )(text)
