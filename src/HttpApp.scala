package supportbot

import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import org.http4s.dsl.*
import org.http4s.{scalatags as _, *}
import org.http4s.server.*
import org.http4s.server.staticcontent.*
import org.http4s.ember.server.*
import org.http4s.server.middleware.EntityLimiter
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

def httpApp(controllers: Vector[TopLevelController])(using config: AppConfig): Resource[IO, Server] =
  for
    given Logger[IO] <- Slf4jLogger.create[IO].toResource
    _                <- info"Starting the server at ${config.host}:${config.port}".toResource
    mappings         <- controllers
                          .appended(StaticAssetsController())
                          .traverse(_.mapping)
                          .toResource
    app = EntityLimiter.httpApp(
      httpApp = Router(mappings*).orNotFound,
      limit = config.maxEntitySizeInBytes,
    )
    server           <- EmberServerBuilder
                          .default[IO]
                          .withHost(config.host)
                          .withPort(config.port)
                          .withHttpApp(app)
                          .withErrorHandler(errorHandler)
                          .build
  yield server

def errorHandler: Logger[IO] ?=> PartialFunction[Throwable, IO[Response[IO]]] =
  err => Logger[IO].error(err)("An error occurred").as(Response[IO](Status.InternalServerError))

final class StaticAssetsController(using Logger[IO], AppConfig) extends TopLevelController:
  def prefix = "static"
  def routes =
    if AppConfig.get.isDev then
      warn"Using local resources".as(fileService[IO](FileService.Config("./resources/static")))
    else
      // assuming production env - we use the resources embedded in the JAR
      info"Using embedded resources".as(resourceServiceBuilder[IO]("/static").toRoutes)
