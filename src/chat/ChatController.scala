package supportbot
package chat

import fs2.{Chunk as _, *}
import fs2.concurrent.Topic
import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, *}
import org.http4s.FormDataDecoder.*
import scalatags.Text.all.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
// import scala.concurrent.duration.*

import rag.vectorstore.*
import rag.*

// TODO: 
// - chat breaks after first message
// - append user queries to the chat

final class ChatController(
  pubSub: PubSub[IO],
)(using
  logger: Logger[IO],
  chatService: ChatService[IO],
  vectorStore: VectorStore[IO],
  embeddingService: EmbeddingService[IO],
) extends HtmxController:
  protected val prefix = "chat"

  private val chatId = "chat-1" // TODO: get from session

  private val userQuery = "What's the daily monitoring routine for the SAFE3 system?"

  private def appPrompt(query: String, context: Option[String]) = Prompt(
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

  private def processQuery(query: ChatQuery): IO[Unit] =
    for
      queryEmbeddings     <- embeddingService.createQueryEmbeddings(Chunk(userQuery, index = 0))
      retrievedEmbeddings <- vectorStore.retrieve(queryEmbeddings).compile.toVector
      contextChunks        = retrievedEmbeddings.map(_.chunk)
      _                   <- info"Retrieved context: ${contextChunks.map(_.toEmbeddingInput)}"

      finalPrompt = appPrompt(
                      query = query.content,
                      context = contextChunks.map(_.toEmbeddingInput).mkString("\n").some,
                    )

      _ <- chatService
             .chatCompletion(finalPrompt)
             .map: chatMsg =>
               PubSub.Message(
                 topicId = chatId,
                 eventType = "chat-response",
                 content = chatMsg.contentDeltas,
               )
             .onComplete:
               Stream(
                 PubSub.Message(
                   topicId = chatId,
                   eventType = "sse-close",
                   content = "Stream completed",
                 ),
               )
             .through(pubSub.publishPipe)
             .compile
             .drain
      _ <- info"Processing the response for $chatId has been completed"
    yield ()

  protected val routes = IO:
    HttpRoutes.of[IO]:
      case GET -> Root =>
        Ok(
          RootLayoutView.view(
            div(
              ChatView.messages(),
              ChatView.chatForm(),
            ),
          ),
        )

      case GET -> Root / "responses" =>
        val eventStream: EventStream[IO] =
          pubSub
            .subscribe(chatId)
            .map: message =>
              ServerSentEvent(
                data = message.content.some,
                eventType = message.eventType.some,
              )

        for
          _   <- info"Subscribing to chat responses for $chatId"
          res <- Ok(eventStream)
        yield res

      case req @ POST -> Root / "query" =>
        for
          query <- req.as[ChatQuery]
          _     <- info"Received query: $query"
          _     <- processQuery(query).start // TODO: use .background / Resource and maybe queue requests
          res   <-
            Ok(ChatView.responseMessage()) 
        yield res

object ChatController:
  def of()(using ChatService[IO], VectorStore[IO], EmbeddingService[IO]): IO[ChatController] =
    for
      given Logger[IO] <- Slf4jLogger.create[IO]
      pubSub           <- PubSub.of[IO]
    yield ChatController(pubSub)

object ChatView extends HtmxView:
  val messagesId = "chat-messages"

  def chatForm() =
    form(
      `hx-post`   := "/chat/query",
      `hx-target` := s"#$messagesId",
      `hx-swap`   := "beforeend scroll:bottom",
    )(
      div(
        input(
          cls         := "w-full p-2 rounded-lg",
          `type`      := "text",
          name        := "content",
          placeholder := "Type a query to the chatbot",
        ),
      ),
    )

  def responseMessage(initialContent: Option[String] = None) =
    div(
      cls := "chat chat-start",
      div(
        cls           := "chat-bubble chat-bubble-primary",
        `hx-ext`      := "sse",
        `sse-connect` := "/chat/responses",
        `sse-swap`    := "chat-response",
        `hx-swap`     := "beforeend scroll:bottom",
      )(
        initialContent.getOrElse(""),
      ),
    )

  def messages() =
    div(
      id := messagesId,
    )(
      div(
        cls   := "chat chat-start",
        div(cls := "chat-bubble chat-bubble-primary", "What kind of nonsense is this"),
      ),
      div(
        cls   := "chat chat-start",
        div(
          cls := "chat-bubble chat-bubble-secondary",
          "Put me on the Council and not make me a Master!??",
        ),
      ),
      div(
        cls   := "chat chat-start",
        div(
          cls := "chat-bubble chat-bubble-accent",
          "That's never been done in the history of the Jedi. It's insulting!",
        ),
      ),
      div(cls := "chat chat-end", div(cls := "chat-bubble chat-bubble-info", "Calm down, Anakin.")),
      div(
        cls   := "chat chat-end",
        div(cls := "chat-bubble chat-bubble-success", "You have been given a great honor."),
      ),
      div(
        cls   := "chat chat-end",
        div(cls := "chat-bubble chat-bubble-warning", "To be on the Council at your age."),
      ),
      div(
        cls   := "chat chat-end",
        div(cls := "chat-bubble chat-bubble-error", "It's never happened before."),
      ),
    )

final case class ChatQuery(content: String)
object ChatQuery:
  given FormDataDecoder[ChatQuery] = (
    field[String]("content")
  ).map(ChatQuery.apply)

trait PubSub[F[_]]:
  def publish(message: PubSub.Message): F[Unit]
  def publishPipe: Pipe[F, PubSub.Message, Unit]
  def subscribe(topicId: String): Stream[F, PubSub.Message]

object PubSub:
  final case class Message(topicId: String, eventType: String, content: String)

  def of[F[_]: Concurrent]: F[PubSub[F]] =
    Topic[F, Message]().map: topic =>
      new PubSub[F]:
        def publish(message: Message): F[Unit]        =
          topic.publish1(message).void
        def publishPipe: Pipe[F, Message, Unit]       =
          topic.publish
        def subscribe(id: String): Stream[F, Message] =
          topic.subscribeUnbounded.filter(_.topicId == id)
