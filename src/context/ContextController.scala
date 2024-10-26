package supportbot
package context

import fs2.{Chunk as _, *}
import cats.effect.*
import cats.syntax.all.*
import org.http4s.{scalatags as _, h2 as _, *}
import org.http4s.FormDataDecoder.*
import scalatags.Text.all.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import scala.concurrent.duration.{span as _, *}
import java.util.UUID
import org.http4s.dsl.impl.QueryParamDecoderMatcher

import context.chat.*
// import supportbot.rag.vectorstore.VectorStoreRepository
import supportbot.rag.DocumentRepository

final class ContextController(using
  logger: Logger[IO],
  contextRepository: ContextRepository[IO],
  documentRepository: DocumentRepository[IO],
  chatService: ChatService,
) extends TopLevelHtmxController:
  import ContextController.*

  protected val prefix = "contexts"

  protected val routes = IO:
    HttpRoutes.of[IO]:
      case GET -> Root =>
        for
          contexts <- contextRepository.getAll
          response <- Ok(ContextView.contextsOverview(contexts))
        yield response

      case GET -> Root / ContextIdVar(contextId) =>
        getContextOrNotFound(contextId): context =>
          Ok(ContextView.view(context, postUrl = s"/$prefix/${context.id}/chat/query"))

      case GET -> Root / ContextIdVar(contextId) / "chat" / "responses" :? QueryIdMatcher(queryId) =>
        getContextOrNotFound(contextId): context =>
          val eventStream: EventStream[IO] = chatService
            .subscribeToQueryResponses(queryId)
            .map: message =>
              ServerSentEvent(
                data = ChatView.responseChunk(message.content).render.some,
                eventType = message.eventType.toString.some,
              )
            .evalTap: msg =>
              debug"SSE message to send: $msg"

          info"Subscribing to chat responses for queryId: $queryId" *>
            Ok(eventStream)

      case req @ POST -> Root / ContextIdVar(contextId) / "chat" / "query" =>
        getContextOrNotFound(contextId): context =>
          for
            query   <- req.as[ChatQuery]
            queryId <- QueryId.of
            _       <- info"Received query: $query"
            // TODO: use .background / Resource and maybe queue requests
            _       <- chatService.processQuery(query, queryId).start
            res     <-
              Ok(
                ChatView.responseMessage(
                  query = query,
                  sseUrl = s"/$prefix/${context.id}/chat/responses?queryId=$queryId",
                  queryResponseEvent = ChatEvent.QueryResponse.toString,
                  queryCloseEvent = ChatEvent.QueryClose.toString,
                ),
              )
          yield res

      // case req @ POST -> Root =>
      //     req.decode[UrlForm] { data =>
      //     for
      //         name <- data.getFirst("name").liftTo[IO](new IllegalArgumentException("name is required"))
      //         description <- data.getFirst("description").liftTo[IO](new IllegalArgumentException("description is required"))
      //         context = Context(UUID.randomUUID(), name, description)
      //         _ <- contextRepository.create(context)
      //         response <- SeeOther(Uri.unsafeFromString(s"/contexts/${context.id}"))
      //     yield response
      //     }

  private def getContextOrNotFound(contextId: ContextId)(fn: ContextInfo => IO[Response[IO]]): IO[Response[IO]] =
    contextRepository
      .get(contextId)
      .flatMap:
        case Some(context) => fn(context)
        case None          => NotFound()

object ContextController:
  def of()(using ContextRepository[IO], DocumentRepository[IO], ChatService): Resource[IO, ContextController] =
    for given Logger[IO] <- Slf4jLogger.create[IO].toResource
    yield ContextController()

  given QueryParamDecoder[QueryId] = QueryParamDecoder[String].emap: str =>
    ParseResult.fromTryCatchNonFatal("Could not parse the UUID")(QueryId(UUID.fromString(str)))

  object QueryIdMatcher extends QueryParamDecoderMatcher[QueryId]("queryId")

  object ContextIdVar:
    def unapply(str: String): Option[ContextId] =
      if str.isEmpty then None
      else scala.util.Try(UUID.fromString(str)).toOption.map(ContextId.apply)

object ContextView extends HtmxView:
  def view(context: ContextInfo, postUrl: String) = RootLayoutView.view(
    div(
      configMenu(),
      ChatView.messages(),
      ChatView.chatForm(postUrl),
    ),
  )

  def contextsOverview(contexts: Vector[ContextInfo]) =
    RootLayoutView.view(
      div(
        h2("Contexts"),
        ul(
          contexts.map: context =>
            li(appLink(s"/contexts/${context.id}", context.name)),
        ),
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
              input(`type` := "file", cls := "file-input file-input-sm w-full max-w-xs"),
            ),
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
