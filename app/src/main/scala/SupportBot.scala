//> using scala 3.5.1
//> using toolkit typelevel:0.1.29
//> using dep org.typelevel::kittens::3.4.0
//> using dep org.typelevel::log4cats-slf4j:2.7.0

//> using dep com.outr::scribe-slf4j2:3.15.2
//> using dep com.outr::scribe-file:3.15.2

//> using dep org.http4s::http4s-dsl::0.23.29
//> using dep org.http4s::http4s-ember-server::0.23.29
//> using dep org.http4s::http4s-scalatags::0.25.2

//> using dep com.lihaoyi::scalatags::0.13.1

//> using dep com.softwaremill.sttp.openai::fs2:0.2.5
//> using dep com.softwaremill.sttp.openai::core:0.2.5
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M19
//> using dep com.softwaremill.sttp.client4::jsoniter:4.0.0-M19

//> using dep io.scalaland::chimney::1.5.0

//> using dep com.github.haifengl::smile-scala:3.1.1

//> using dep dev.langchain4j:langchain4j:0.36.2
//> using dep dev.langchain4j:langchain4j-document-parser-apache-tika:0.36.2

///> using dep org.apache.pdfbox:pdfbox:3.0.3, langchain4j-document-parser-apache-tika uses different version

//> using dep com.davegurnell::unindent:1.8.0

//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core::2.31.3
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.31.3

//> using dep com.slack.api:slack-app-backend:1.44.2

//> using test.dep com.dimafeng::testcontainers-scala-munit::0.41.4

package supportbot

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.*
import sttp.openai.OpenAI
import sttp.model.Uri.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

import supportbot.rag.*
import supportbot.rag.ingestion.*
import supportbot.rag.vectorstore.*
import supportbot.home.*
import supportbot.clickhouse.*
import supportbot.chat.*
import supportbot.integrations.slack.*

object SupportBot extends ResourceApp.Forever:
  def run(args: List[String]): Resource[IO, Unit] =
    for
      given AppConfig                 <- AppConfig.load.toResource
      _                               <- AppLogger.configure.toResource
      given Logger[IO]                <- Slf4jLogger.create[IO].toResource
      given SttpBackend               <- SttpBackend.resource
      given ClickHouseClient[IO]       = SttpClickHouseClient.of
      given ContextRepository[IO]     <- ClickHouseContextRepository.of.toResource
      given DocumentRepository[IO]    <- ClickHouseDocumentRepository.of.toResource
      given VectorStoreRepository[IO] <- ClickHouseVectorStore.of.toResource
      (
        given ChatCompletionService[IO],
        given EmbeddingService[IO],
      )                                = inferenceServicesOf
      given IngestionService[IO]      <- ClickHouseIngestionService.of.toResource
      given ChatService[IO]           <- ChatServiceImpl.of()

      _ <- runInitialHealthChecks().toResource

      // state-changing side effects (!)
      _ <- ClickHouseMigrator.migrate().toResource
      _ <- Fixtures.loadFixtures().toResource

      // slackCmdMapper     <- SlackCommandMapperService.of
      // slackBotController <- SlackBotController.of(
      //                         commandMapper = slackCmdMapper,
      //                         signingSecret = AppConfig.get.slack.signingSecret,
      //                       )

      contextController <- ContextController.of()
      homeController     = HomeController()

      _ <- httpApp(
             controllers = Vector(
               contextController,
               homeController,
              //  slackBotController,
             ),
           )
    yield ()

  private def runInitialHealthChecks()(using clickHouseClient: ClickHouseClient[IO], logger: Logger[IO]) =
    val healthChecks = Vector(
      "ClickHouse" -> clickHouseClient.healthCheck,
    )

    healthChecks
      .traverse: (name, check) =>
        check.attempt.flatMap:
          case Right(_) => info"$name is healthy"
          case Left(e)  =>
            logger.error(e)(s"$name is unhealthy, check your connection. Stopping the app") *> IO.raiseError(e)
      .void

  private def inferenceServicesOf(using AppConfig, SttpBackend) =
    AppConfig.get.inferenceEngine match
      case InferenceEngine.OpenAIOllama(url) =>
        given OpenAI = OpenAI("ollama", uri"$url")
        (SttpOpenAIChatCompletionService(), SttpOpenAIEmbeddingService())
