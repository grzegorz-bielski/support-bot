//> using scala 3.5.1
//> using toolkit typelevel:0.1.28
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
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M17

//> using dep io.scalaland::chimney::1.5.0

//> using dep com.github.haifengl::smile-scala:3.1.1

//> using dep dev.langchain4j:langchain4j:0.35.0
//> using dep dev.langchain4j:langchain4j-document-parser-apache-tika:0.35.0

///> using dep org.apache.pdfbox:pdfbox:3.0.3, langchain4j-document-parser-apache-tika uses different version

//> using dep com.davegurnell::unindent:1.8.0

//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core::2.31.1
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.31.1

//> using test.dep com.dimafeng::testcontainers-scala-munit::0.41.4

package supportbot

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.*
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.openai.OpenAI
import sttp.model.Uri.*
import java.io.File

import supportbot.rag.*
import supportbot.rag.ingestion.*
import supportbot.rag.vectorstore.*
import supportbot.home.*
import supportbot.clickhouse.*
import supportbot.context.*
import supportbot.context.chat.*
import java.util.UUID

object SupportBot extends ResourceApp.Forever:
  def run(args: List[String]): Resource[IO, Unit] =
    for
      given AppConfig                 <- AppConfig.load.toResource
      _                               <- AppLogger.configure.toResource
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

      // state-changing side effects
      _ <- ClickHouseMigrator.migrate().toResource
      _ <- Fixtures.loadFixtures.toResource

      given ChatService <- ChatService.of()
      contextController <- ContextController.of()
      homeController     = HomeController()

      _ <- httpApp(
             controllers = Vector(
               contextController,
               homeController,
             ),
           )
    yield ()

  private def inferenceServicesOf(using AppConfig, SttpBackend) =
    AppConfig.get.inferenceEngine match
      case InferenceEngine.OpenAIOllama(url) =>
        given OpenAI = OpenAI("ollama", uri"$url")
        (SttpOpenAIChatCompletionService(), SttpOpenAIEmbeddingService())
