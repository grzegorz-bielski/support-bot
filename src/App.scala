//> using scala 3.5.1
//> using toolkit typelevel:0.1.28
//> using dep com.softwaremill.sttp.openai::fs2:0.2.3
//> using dep com.softwaremill.sttp.openai::core:0.2.3
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M17
//> using dep com.github.haifengl::smile-scala:3.1.1
//> using dep org.apache.pdfbox:pdfbox:3.0.3
//> using dep com.davegurnell::unindent:1.8.0
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core::2.30.15
//> using dep com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.30.15

package supportbot

import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.effect.*

import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import com.github.plokhotnyuk.jsoniter_scala.core.*
import sttp.openai.OpenAI
import sttp.model.Uri.*

import supportbot.rag.*
import supportbot.chat.*
import supportbot.clickhouse.ClickHouseClient

// TODO:
// - vector store integration
// - UI

object Main extends ResourceApp.Simple:
  def run =
    for
      given SttpBackend <- SttpBackend.resource

      vectorStore = rag.clickhouse.ClickHouseVectorStore.sttpBased(
        ClickHouseClient.Config(
          url = "http://localhost:8123",
          username = "default",
          password = "default"
        )
      )
      _ <- vectorStore.migrate().toResource

      openAIProtocol = OpenAI("ollama", uri"http://localhost:11434/v1")
      chatService = SttpOpenAIChatService(openAIProtocol, model = Model("llama3.1"))
      embeddingService = SttpOpenAIEmbeddingService(openAIProtocol, model = Model("snowflake-arctic-embed"))

      _ <- program(vectorStore, chatService, embeddingService).toResource
    yield ()

  val userQuery = "How do I solve manual resolution with unresolved_games reason?"

  def appPrompt(query: String, context: Option[String]) = Prompt(
    taskContext = "You are an expert Q&A system that is trusted around the world.".some,
    toneContext = "You should maintain a professional and friendly tone.".some,
    taskDescription = Vector(
      "Some rules to follow:",
      "- Always answer the query using the provided, context information, and not prior knowledge",
      "- Only answer if you know the answer with certainty.",
      "- Do not try to resolve problems mentioned in the context.",
      "- If you are unsure how to respond, say \"Sorry, I didn't understand that. Could you rephrase your question?\"",
      "- If you are unable to answer the question, say \"Sorry, I don't have that information.\""
    ).mkString("\n").some,
    queryContext = s"<context> $context </context>".some,
    query = s"<query> $query </query>",
    precognition = Vector(
      "Before you answer, take into consideration the context.",
      "Then, pull the most relevant fragment from the context and consider whether it answers the user's query provided below or whether it lacks sufficient detail."
    ).mkString("\n").some
  )

  def program(
      vectorStore: VectorStore[IO],
      chatService: ChatService[IO],
      embeddingService: EmbeddingService[IO]
  ): IO[Unit] =
    for
      // offline - parsing and indexing
      _ <- IO.println("Chunking PDF")
      document <- DocumentLoader.loadPDF("./resources/SAFE3 - Support Guide-v108-20240809_102738.pdf")
      _ <- IO.println(s"Creating embeddings. It may take a while...")
      indexEmbeddings <- embeddingService.createIndexEmbeddings(document)
      _ <- IO.println(s"Created ${indexEmbeddings.size} embeddings.")
      _ <- vectorStore.store(indexEmbeddings)

      // online - chat
      queryEmbeddings <- embeddingService.createQueryEmbeddings(Chunk(userQuery))
      contextChunk <- vectorStore.retrieve(queryEmbeddings)
      _ <- IO.println(s"Retrieved context: ${contextChunk.map(_.toEmbeddingInput)}")

      - <- IO.println("Asking for chat completion")
      res <- chatService.runChatCompletion(
        appPrompt(
          query = userQuery,
          context = contextChunk.map(_.toEmbeddingInput).mkString("\n").some
        )
      )
    yield ()
