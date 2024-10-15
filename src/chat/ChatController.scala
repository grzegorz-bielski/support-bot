package supportbot
package chat

import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, *}
import scalatags.Text.all.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*

import rag.vectorstore.*
import rag.*

final class ChatController(using
  logger: Logger[IO],
  chatService: ChatService[IO],
  vectorStore: VectorStore[IO],
  embeddingService: EmbeddingService[IO],
) extends HtmxController:
  val prefix = "chat"

  val userQuery = "What's the daily monitoring routine for the SAFE3 system?"

  def appPrompt(query: String, context: Option[String]) = Prompt(
    taskContext = "You are an expert Q&A system that is trusted around the world.".some,
    toneContext = "You should maintain a professional and friendly tone.".some,
    taskDescription = Vector(
      "Some rules to follow:",
      "- Always answer the query using the provided, context information, and not prior knowledge",
      "- Only answer if you know the answer with certainty.",
      "- Do not try to resolve problems mentioned in the context.",
      "- If you are unsure how to respond, say \"Sorry, I didn't understand that. Could you rephrase your question?\"",
      "- If you are unable to answer the question, say \"Sorry, I don't have that information.\"",
    ).mkString("\n").some,
    queryContext = s"<context> $context </context>".some,
    query = s"<query> $query </query>",
    precognition = Vector(
      "Before you answer, take into consideration the context.",
      "Then, pull the most relevant fragment from the context and consider whether it answers the user's query provided below or whether it lacks sufficient detail.",
    ).mkString("\n").some,
  )

  val routes = IO:
    HttpRoutes.of[IO]:
      case GET -> Root =>
        for
        //   queryEmbeddings     <- embeddingService.createQueryEmbeddings(Chunk(userQuery, index = 0))
        //   retrievedEmbeddings <- vectorStore.retrieve(queryEmbeddings).compile.toVector
        //   contextChunks        = retrievedEmbeddings.map(_.chunk)
        //   _                   <- info"Retrieved context: ${contextChunks.map(_.toEmbeddingInput)}"

        //   - <- info"Asking for chat completion"
        //   _ <- chatService.runChatCompletion(
        //          appPrompt(
        //            query = userQuery,
        //            context = contextChunks.map(_.toEmbeddingInput).mkString("\n").some,
        //          ),
        //        )

          res <- Ok(
                   RootLayoutView.view(
                     div("chat"),
                   ),
                 )
        yield res

object ChatController:
  def of()(using ChatService[IO], VectorStore[IO], EmbeddingService[IO]): IO[ChatController] =
    for given Logger[IO] <- Slf4jLogger.create[IO]
    yield ChatController()
