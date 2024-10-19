package supportbot
package chat

import fs2.{Chunk as _, *}
import fs2.concurrent.Topic
import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, h2 as _, *}
import org.http4s.FormDataDecoder.*
import scalatags.Text.all.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import scala.concurrent.duration.{span as _, *}

import rag.vectorstore.*
import rag.*
import java.util.UUID
import org.http4s.dsl.impl.QueryParamDecoderMatcher

final class ChatController(
  pubSub: PubSub[IO],
)(using
  logger: Logger[IO],
  chatService: ChatService[IO],
  vectorStore: VectorStoreRepository[IO],
  embeddingService: EmbeddingService[IO],
) extends HtmxController:
  import ChatController.*

  protected val prefix = "chat"

  // TODO:
  // - create new chat and persist it's id
  // - chat history
  // - do not hardcode chatId

  private val chatId = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479")

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

  private def processQuery(query: ChatQuery, queryId: UUID, chatId: UUID): IO[Unit] =
    for
      queryEmbeddings     <- embeddingService.createQueryEmbeddings(Chunk(query.content, index = 0))
      retrievedEmbeddings <- vectorStore.retrieve(queryEmbeddings).compile.toVector

      topicId = queryId.toString

      // TODO: group retrieved embeddings by (documentId, version, fragmentIndex) and show them in chat

      contextChunks = retrievedEmbeddings.map(_.chunk)
      _            <- debug"Retrieved context: ${contextChunks.map(_.toEmbeddingInput)}"

      finalPrompt = appPrompt(
                      query = query.content,
                      context = contextChunks.map(_.toEmbeddingInput).mkString("\n").some,
                    )

      _ <- chatService
             .chatCompletion(finalPrompt)
             .map: chatMsg =>
               PubSub.Message(
                 topicId = topicId,
                 eventType = ChatView.queryResponseEvent,
                 content = chatMsg.contentDeltas,
               )
             .onComplete:
               Stream(
                 PubSub.Message(
                   topicId = topicId,
                   eventType = ChatView.queryCloseEvent,
                   content = "Stream completed",
                 ),
               )
             .evalTap: chatMsg =>
               debug"Received chat message: $chatMsg"
             .evalTap(pubSub.publish)
             .compile
             .drain
      _ <- info"Processing the response for queryId: $queryId has been completed"
    yield ()

  protected val routes = IO:
    HttpRoutes.of[IO]:
      case GET -> Root =>
        Ok(ChatView.view())

      case GET -> Root / "responses" :? ChatIdMatcher(chatId) :? QueryIdMatcher(queryId) =>
        val topicId                      = queryId.toString
        val eventStream: EventStream[IO] =
          pubSub
            .subscribe(topicId)
            .map: message =>
              ServerSentEvent(
                data = ChatView.responseChunk(message.content).render.some,
                eventType = message.eventType.some,
              )
            .evalTap: msg =>
              debug"SSE message to send: $msg"
            .timeout(5.minutes)

        for
          _   <- info"Subscribing to chat responses for queryId: $queryId"
          res <- Ok(eventStream)
        yield res

      case req @ POST -> Root / "query" =>
        for
          query   <- req.as[ChatQuery]
          queryId <- IO.randomUUID
          _       <- info"Received query: $query"
          // TODO: use .background / Resource and maybe queue requests
          _       <- processQuery(query, queryId, chatId).start
          res     <-
            Ok(ChatView.responseMessage(query, queryId, chatId))
        yield res

object ChatController:
  def of()(using ChatService[IO], VectorStoreRepository[IO], EmbeddingService[IO]): Resource[IO, ChatController] =
    for
      given Logger[IO] <- Slf4jLogger.create[IO].toResource
      pubSub           <- PubSub.resource[IO]
    yield ChatController(pubSub)

  given QueryParamDecoder[UUID] = QueryParamDecoder[String].emap: str =>
    ParseResult.fromTryCatchNonFatal("Could not parse the UUID")(UUID.fromString(str))

  object ChatIdMatcher  extends QueryParamDecoderMatcher[UUID]("chatId")
  object QueryIdMatcher extends QueryParamDecoderMatcher[UUID]("queryId")

object ChatView extends HtmxView:
  val messagesId = "chat-messages"

  val queryResponseEvent = "query-response"
  val queryCloseEvent    = "query-close"

  def view() = RootLayoutView.view(
    div(
      ChatView.configMenu(),
      ChatView.messages(),
      ChatView.chatForm(),
    ),
  )

  def configMenu() =
    // TODO: get documents from the database
    val documents = Vector(
      "SAFE3 - Support Guide-v108-20240809_102738.pdf",
    )

    div(
      cls := "py-4",
      div(
        tabindex := "0",
        cls      := "collapse collapse-open border-base-300 bg-base-200 border",
        div(cls := "collapse-title text-xl font-medium", "Knowledge Base"),
        div(
          cls   := "collapse-content",
          div(
            h3("Files"),
            ul(
              cls := "menu menu-xs bg-base-200 rounded-lg w-full max-w-s",
              documents.map: document =>
                li(
                  a(
                    documentIcon(),
                    document,
                  ),
                ),
            ),
            form(
              input(`type` := "file", cls := "file-input file-input-sm w-full max-w-xs")
            )
          ),
        ),
      ),
    )

  def documentIcon() =
    import scalatags.Text.svgTags.{attr as _, *}
    import scalatags.Text.svgAttrs.*

    svg(
      xmlns                := "http://www.w3.org/2000/svg",
      fill                 := "none",
      viewBox              := "0 0 24 24",
      attr("stroke-width") := "1.5",
      stroke               := "currentColor",
      cls                  := "h-4 w-4",
      path(
        attr("stroke-linecap")  := "round",
        attr("stroke-linejoin") := "round",
        d                       := "M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z",
      ),
    )

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

  def responseMessage(
    query: ChatQuery,
    queryId: UUID,
    chatId: UUID,
  ) =
    div(
      div(
        cls := "chat chat-end",
        div(cls := "chat-bubble chat-bubble-secondary", query.content),
      ),
      div(
        cls := "chat chat-start",
        div(
          cls           := "chat-bubble chat-bubble-primary",
          `hx-ext`      := "sse",
          `sse-connect` := s"/chat/responses?chatId=$chatId&queryId=$queryId",
          `sse-swap`    := queryResponseEvent,
          `sse-close`   := queryCloseEvent,
          `hx-swap`     := "beforeend scroll:bottom",
        )(),
      ),
    )

  def responseChunk(content: String) =
    span(sanitizeChunk(content))

  private def sanitizeChunk(input: String) =
    // TODO: this could be potentially dangerous, use a proper HTML sanitizer
    raw(
      input
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;")
        .replaceAll("'", "&#x27;")
        .replaceAll("/", "&#x2F;")
        .replaceAll("\n", br().render),
    )

  def messages() =
    div(
      id := messagesId,
    )(
      div(
        cls := "chat chat-start",
        div(cls := "chat-bubble chat-bubble-primary", "Hello, how can I help you?"),
      ),
    )

final case class ChatQuery(content: String)
object ChatQuery:
  given FormDataDecoder[ChatQuery] = (
    field[String]("content")
  ).map(ChatQuery.apply)

trait PubSub[F[_]]:
  def publish(message: PubSub.Message): F[Unit]
  def subscribe(topicId: String): Stream[F, PubSub.Message]

object PubSub:
  final case class Message(topicId: String, eventType: String, content: String)

  def resource[F[_]: Concurrent]: Resource[F, PubSub[F]] =
    Resource
      .make(Topic[F, Message]())(_.close.void)
      .map: topic =>
        new PubSub[F]:
          def publish(message: Message): F[Unit]        =
            topic.publish1(message).void
          def subscribe(id: String): Stream[F, Message] =
            topic.subscribeUnbounded.filter(_.topicId == id)
