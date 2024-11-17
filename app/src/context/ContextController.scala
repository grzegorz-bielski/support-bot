package supportbot
package context

import fs2.{Chunk as _, *}
import fs2.io.*
import fs2.io.file.Files
import cats.effect.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import org.http4s.{scalatags as _, h2 as _, *}
import org.http4s.FormDataDecoder.*
import org.http4s.dsl.impl.QueryParamDecoderMatcher
import org.http4s.implicits.*
import org.http4s.headers.Location
import org.http4s.multipart.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import scalatags.Text.all.*
import scala.concurrent.duration.{span as _, *}
import java.util.UUID

import context.chat.*
import supportbot.rag.vectorstore.{VectorStoreRepository, RetrieveOptions, LookupRange}
import supportbot.rag.*
import supportbot.rag.ingestion.IngestionService

final class ContextController(using
  logger: Logger[IO],
  contextRepository: ContextRepository[IO],
  documentRepository: DocumentRepository[IO],
  ingestionService: IngestionService[IO],
  chatService: ChatService,
  appConfig: AppConfig,
) extends TopLevelHtmxController:
  import ContextController.*

  protected val prefix = "contexts"

  private val fileFieldName = "file"

  protected val routes = IO:
    val documentDeleteUrl = (doc: Document.Info) => s"/$prefix/${doc.contextId}/documents/${doc.id}"

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
        getContextOrNotFound(contextId): contextInfo =>
          for
            documents <- documentRepository.getAll(contextInfo.id)
            response  <- Ok(
                           ContextView.view(
                             contextInfo = contextInfo,
                             uploadUrl = s"/$prefix/${contextInfo.id}/documents/upload",
                             chatPostUrl = s"/$prefix/${contextInfo.id}/chat/query",
                             contextUpdateUrl = s"/$prefix/${contextInfo.id}/update",
                             documents = documents,
                             fileFieldName = fileFieldName,
                             documentDeleteUrl = documentDeleteUrl,
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
          println("contextId: " -> contextId)
          for
            query   <- req.as[ChatQuery]
            queryId <- QueryId.of
            // TODO: use .background / Resource and maybe queue requests
            _       <- chatService
                         .processQuery(
                           ChatService.Input(
                             contextId = context.id,
                             query = query,
                             queryId = queryId,
                             promptTemplate = context.promptTemplate,
                             retrieveOptions = RetrieveOptions(
                               topK = 15,
                               fragmentLookupRange = LookupRange(5, 5),
                             ),
                             chatModel = context.chatModel,
                             embeddingsModel = context.embeddingsModel,
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

      case req @ POST -> Root / ContextIdVar(contextId) / "update" =>
        getContextOrNotFound(contextId): context =>
          for
            contextInfo <- req.as[ContextInfoFormDto].map(_.asContextInfo(context.id))
            _           <- info"Updating context: $contextInfo"
            response    <- contextInfo.fold(
                             BadRequest(_),
                             contextRepository.createOrUpdate(_) *> Ok(),
                           )
          yield response

      case req @ POST -> Root / ContextIdVar(contextId) / "documents" / "upload" =>
        getContextOrNotFound(contextId): context =>
          EntityDecoder
            .mixedMultipartResource[IO]()
            .use: decoder =>
              req.decodeWith(decoder, strict = true): multipart =>
                val ingestedDocuments = multipart.parts
                  .filter(_.name.contains(fileFieldName))
                  .parTraverse: part =>
                    ingestionService.ingest(
                      IngestionService.Input(
                        contextId = context.id,
                        documentName = DocumentName(part.filename.getOrElse("unknown")),
                        embeddingsModel = context.embeddingsModel,
                        content = part.body,
                      ),
                    )

                ingestedDocuments.flatMap: docs =>
                  Ok(
                    ContextView.uploadedDocuments(docs, documentDeleteUrl = documentDeleteUrl),
                  )

      case req @ DELETE -> Root / ContextIdVar(contextId) / "documents" / DocumentIdVar(documentId) =>
        getContextOrNotFound(contextId): context =>
          // TODO: validate if the document belongs to the context
          for
            _        <- documentRepository.delete(documentId)
            _        <- info"Deleted document: $documentId"
            response <- Ok()
          yield response

  private def getContextOrNotFound(contextId: ContextId)(fn: ContextInfo => IO[Response[IO]]): IO[Response[IO]] =
    contextRepository
      .get(contextId)
      .flatMap:
        case Some(context) => fn(context)
        case None          => NotFound()

object ContextController:
  def of()(using
    ContextRepository[IO],
    DocumentRepository[IO],
    ChatService,
    IngestionService[IO],
    AppConfig,
  ): Resource[IO, ContextController] =
    for given Logger[IO] <- Slf4jLogger.create[IO].toResource
    yield ContextController()

  given QueryParamDecoder[QueryId] = QueryParamDecoder[String].emap: str =>
    ParseResult.fromTryCatchNonFatal("Could not parse the UUID")(QueryId(UUID.fromString(str)))

  object QueryIdMatcher extends QueryParamDecoderMatcher[QueryId]("queryId")

  object ContextIdVar:
    def unapply(str: String): Option[ContextId] =
      if str.isEmpty then None
      else scala.util.Try(UUID.fromString(str)).toOption.map(ContextId.apply)

  object DocumentIdVar:
    def unapply(str: String): Option[DocumentId] =
      if str.isEmpty then None
      else scala.util.Try(UUID.fromString(str)).toOption.map(DocumentId.apply)
