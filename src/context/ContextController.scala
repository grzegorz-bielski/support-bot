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
import org.http4s.implicits.*
import org.http4s.headers.Location

import context.chat.*
import supportbot.rag.vectorstore.{VectorStoreRepository, RetrieveOptions, LookupRange}
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

      case GET -> Root / "new" =>
        for
          context <- ContextInfo.default
          _       <- contextRepository.createOrUpdate(context)
          response = Response[IO]()
                       .withStatus(Status.SeeOther)
                       .withHeaders(Location(Uri.unsafeFromString(s"/$prefix/${context.id}")))
        //  .withHeaders(Location(uri"/$prefix/${context.id.toString}")) -- implementation is missing ??
        yield response

      case GET -> Root / ContextIdVar(contextId) =>
        getContextOrNotFound(contextId): context =>
          for
            documents <- documentRepository.getAll(context.id)
            response  <- Ok(
                           ContextView.view(
                             context = context,
                             postUrl = s"/$prefix/${context.id}/chat/query",
                             documents = documents,
                           ),
                         )
          yield response

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
            _       <- chatService
                         .processQuery(
                           query = query,
                           queryId = queryId,
                           promptTemplate = context.promptTemplate,
                           retrieveOptions = RetrieveOptions(
                             topK = 15,
                             fragmentLookupRange = LookupRange(5, 5),
                           ),
                         )
                         .start
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
