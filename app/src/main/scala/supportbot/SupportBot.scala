package supportbot

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.*
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
import supportbot.inference.*

object SupportBot extends ResourceApp.Forever:
  def run(args: List[String]): Resource[IO, Unit] =
    for
      given AppConfig   <- AppConfig.load.toResource
      _                 <- AppLogger.configure.toResource
      given Logger[IO]  <- Slf4jLogger.create[IO].toResource
      given SttpBackend <- SttpBackend.resource

      given ClickHouseClient[IO]       = SttpClickHouseClient.of
      given ContextRepository[IO]     <- ClickHouseContextRepository.of.toResource
      given DocumentRepository[IO]    <- ClickHouseDocumentRepository.of.toResource
      given VectorStoreRepository[IO] <- ClickHouseVectorStore.of.toResource

      inferenceModule                 = InferenceModule.of
      given ChatCompletionService[IO] = inferenceModule.chatCompletionService
      given EmbeddingService[IO]      = inferenceModule.embeddingService

      given IngestionService[IO] <- ClickHouseIngestionService.of.toResource
      given ChatService[IO]      <- ChatServiceImpl.of()

      contextController <- ContextController.of()
      homeController     = HomeController()

      // integrations
      given SlackCommandsService[IO] <- SlackCommandsService.of
      slackBotController            <- SlackBotController.of

      _ <- runInitialHealthChecks().toResource

      // state-changing side effects (!)
      _ <- ClickHouseMigrator.migrate().toResource
      _ <- Fixtures.loadFixtures().toResource

      _ <- httpApp(
             controllers = Vector(
               contextController,
               homeController,
               slackBotController,
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
