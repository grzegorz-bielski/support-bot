package supportbot

import cats.effect.*
import cats.effect.std.*
import cats.syntax.all.*
import scribe.Level
import com.comcast.ip4s.*

final case class AppConfig(
  host: Host,
  port: Port,
  env: EnvType,
  logLevel: Level,
  logPath: Option[String],
  loadFixtures: Boolean,
  maxEntitySizeInBytes: Long,
  inferenceEngine: InferenceEngine,
  clickhouse: ClickhouseConfig,
):
  def isDev = env == EnvType.Local

object AppConfig:
  inline def get(using appConfig: AppConfig): AppConfig = appConfig

  def load: IO[AppConfig] =
    for
      env      <- Env[IO].get("ENV").map(_.flatMap(EnvType.fromString).getOrElse(EnvType.Local))
      logLevel <- Env[IO].get("LOG_LEVEL").map(_.flatMap(Level.get).getOrElse(Level.Info))
      path     <- Env[IO].get("LOG_PATH")
    yield AppConfig(
      host = ipv4"0.0.0.0",
      port = port"8081",
      env = env,
      logLevel = logLevel,
      logPath = path,
      loadFixtures = false,
      maxEntitySizeInBytes = 1073741824L, // 1GiB
      inferenceEngine = InferenceEngine.OpenAIOllama(
        url = "http://localhost:11434/v1"
      ),
      clickhouse = ClickhouseConfig(
        url = "http://localhost:8123",
        username = "default",
        password = "default",
        database = "default",
        resetOnStart = true,
      ),
    )

enum EnvType:
  case Local, Prod

object EnvType:
  def fromString(str: String): Option[EnvType] =
    EnvType.values.find(_.toString.equalsIgnoreCase(str))

enum InferenceEngine:
  case OpenAIOllama(
    url: String
  )

final case class ClickhouseConfig(
  url: String,
  username: String,
  password: String,
  database: String,
  resetOnStart: Boolean,
)
