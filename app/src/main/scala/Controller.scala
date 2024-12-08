package supportbot

import cats.effect.*
import cats.syntax.all.*
import org.http4s.dsl.*
import org.http4s.{scalatags as _, *}
import org.http4s.scalatags.ScalatagsInstances

trait Controller extends Http4sDsl[IO]
trait HtmxController extends Controller, ScalatagsInstances, HtmxView

trait TopLevelController extends Controller:
  protected def prefix: String
  protected def routes: IO[HttpRoutes[IO]]

  final def mapping: IO[(String, HttpRoutes[IO])] =
    routes.map(prefix -> _)

trait TopLevelHtmxController extends TopLevelController, ScalatagsInstances, HtmxView
