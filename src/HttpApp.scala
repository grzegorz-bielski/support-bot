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
import scala.util.control.NonFatal

def httpApp(
  host: Host = ipv4"0.0.0.0",
  port: Port = port"8081",
  controllers: Vector[TopLevelController],
): Resource[IO, Server] =
  for
    given Logger[IO] <- Slf4jLogger.create[IO].toResource
    _                <- info"Starting the server at $host:$port".toResource
    mappings         <- controllers
                          .appended(StaticAssetsController())
                          .traverse(_.mapping)
                          .toResource
    app               = Router(mappings*).orNotFound
    server           <- EmberServerBuilder
                          .default[IO]
                          .withHost(host)
                          .withPort(port)
                          .withHttpApp(app)
                          .withErrorHandler(errorHandler)
                          .build
  yield server

def errorHandler: Logger[IO] ?=> PartialFunction[Throwable, IO[Response[IO]]] =
  err => Logger[IO].error(err)("An error occurred").as(Response[IO](Status.InternalServerError))

final class StaticAssetsController(using Logger[IO]) extends TopLevelController:
  def prefix = "static"
  def routes = Env[IO]
    .get("ENV")
    .flatMap:
      case Some("local") =>
        warn"Using local resources".as(fileService[IO](FileService.Config("./resources/static")))
      case _             =>
        // assuming production env - we use the resources embedded in the JAR
        info"Using embedded resources".as(resourceServiceBuilder[IO]("/static").toRoutes)
