package supportbot

import cats.syntax.all.*
import cats.effect.*
import cats.effect.std.*
import scribe.*
import scribe.file.*
import scribe.format.*
import scribe.output.LogOutput
import scribe.output.TextOutput
import scribe.writer.ConsoleWriter
import scribe.writer.{Writer as ScribeWriter}

object AppLogger:
  def configure: IO[Unit] =
  for
    logLevel <- Env[IO].get("LOG_LEVEL").map(_.flatMap(Level.get).getOrElse(Level.Info))
    path     <- Env[IO].get("LOG_PATH")
    writer    = path match
                  case Some(path) => AppFileWriter(path)
                  case None       => ConsoleWriter

    _ <- createFrom(writer, logLevel)
  yield ()

  def createFrom(writer: ScribeWriter, logLevel: Level): IO[Unit] =
    IO
      .delay:
        Logger.root
          .clearHandlers()
          .withHandler(
            writer = writer,
            minimumLevel = logLevel.some,
          )
          .replace()
      .void

  private def AppFileWriter(path: String) =
    val size = 1024 * 1000 * 1000L
    val file = "app" % maxSize(max = size, separator = ".") % ".log"

    FileWriter(path / file)
