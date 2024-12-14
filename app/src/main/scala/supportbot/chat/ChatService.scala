package supportbot
package chat

import fs2.{Chunk as _, io as _, *}
import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import scala.concurrent.duration.{span as _, *}
import io.scalaland.chimney.dsl.*
import io.scalaland.chimney.*
import java.util.UUID
import java.time.Duration

import supportbot.rag.vectorstore.*
import supportbot.rag.*

trait ChatService[F[_]]:
  /** Start the query processing. This might take some time to complete, so it usually should be run in the background
    * fiber.
    *
    * @param input
    *   The input for the query.
    */
  def processQuery(input: ChatService.Input): F[Unit]

  /** Subscribe to the query responses started in `processQuery`.
    *
    * @param queryId
    *   The query id to subscribe to.
    * @return
    *   A stream of responses.
    */
  def subscribeToQueryResponses(queryId: QueryId): Stream[F, ChatService.Response]

  /** Ask a question and wait for the response.
    *
    * @param input
    *   The input for the query.
    * @return
    *   The response content.
    */
  final def ask(input: ChatService.Input)(using Concurrent[F]): F[String] =
    Concurrent[F]
      .background(processQuery(input))
      .use: _ =>
        subscribeToQueryResponses(input.queryId)
          .collectWhile:
            case ChatService.Response.Partial(_, content) => content
          .compile
          .string

object ChatService:
  final case class Input(
    contextId: ContextId,
    query: ChatQuery,
    queryId: QueryId,
    promptTemplate: PromptTemplate,
    retrievalSettings: RetrievalSettings,
    chatModel: Model,
    embeddingsModel: Model,
  )

  extension (info: ContextInfo)
    def toChatInput(query: ChatQuery, queryId: QueryId): Input =
      info
        .into[Input]
        .withFieldRenamed(_.id, _.contextId)
        .withFieldConst(_.query, query)
        .withFieldConst(_.queryId, queryId)
        .transform

  trait WithQueryId:
    def queryId: QueryId

  enum ResponseType:
    case Partial, Finished

  enum Response(val eventType: ResponseType) extends WithQueryId:
    case Partial(queryId: QueryId, content: String) extends Response(ResponseType.Partial)

    case Finished(queryId: QueryId) extends Response(ResponseType.Finished)

final class ChatServiceImpl(
  pubSub: PubSub[IO],
)(using
  logger: Logger[IO],
  chatCompletionService: ChatCompletionService[IO],
  vectorStore: VectorStoreRepository[IO],
  embeddingService: EmbeddingService[IO],
) extends ChatService[IO]:
  import ChatService.*
  import ChatServiceImpl.*

  // private def appPrompt(query: String, context: Option[String]) = Prompt(
  //   taskContext = "You are an expert Q&A system that is trusted around the world.".some,
  //   toneContext = "You should maintain a professional and friendly tone.".some,
  //   taskDescription = Vector(
  //     "Some rules to follow:",
  //     "- Always answer the query using the provided, context information, and not prior knowledge",
  //     "- Only answer if you know the answer with certainty.",
  //     "- Do not try to resolve problems mentioned in the context.",
  //     "- If you are unsure how to respond, say \"Sorry, I didn't understand that. Could you rephrase your question?\"",
  //     "- If you are unable to answer the question, say \"Sorry, I don't have that information.\"",
  //   ).mkString("\n").some,
  //   queryContext = s"<context> $context </context>".some,
  //   query = s"<query> $query </query>",
  //   precognition = Vector(
  //     "Before you answer, take into consideration the context.",
  //     "Then, pull the most relevant fragment from the context and consider whether it answers the user's query provided below or whether it lacks sufficient detail.",
  //   ).mkString("\n").some,
  // )

  def processQuery(input: ChatService.Input): IO[Unit] =
    import input.*

    for
      _                   <- info"Processing the response for queryId: $queryId has started."
      processingStarted   <- IO.realTimeInstant
      queryEmbeddings     <- embeddingService.createQueryEmbeddings(
                               contextId = contextId,
                               chunk = Chunk(query.content, index = 0),
                               model = embeddingsModel,
                             )
      retrievedEmbeddings <- vectorStore.retrieve(queryEmbeddings, retrievalSettings).compile.toVector

      // _ <- info"Retrieved embeddings: $retrievedEmbeddings"

      // TODO: group retrieved embeddings by (documentId, version, fragmentIndex) and show them in chat

      contextChunks = retrievedEmbeddings.map(_.chunk)
      _            <- info"Retrieved context: ${contextChunks.map(_.toEmbeddingInput)}"

      prompt =
        Prompt(
          query = query.content,
          queryContext = contextChunks.map(_.toEmbeddingInput).mkString("\n").some,
          template = promptTemplate,
        )

      _ <- chatCompletionService
             .chatCompletion(prompt, model = chatModel)
             .map: chatMsg =>
               Response.Partial(
                 queryId = queryId,
                 content = chatMsg.contentDeltas,
               )
             .onComplete:
               Stream(Response.Finished(queryId = queryId))
             .evalTap: chatMsg =>
               debug"Received chat message: $chatMsg"
             .evalTap(pubSub.publish)
             .compile
             .drain

      processingEnded   <- IO.realTimeInstant
      processingDuration = Duration.between(processingStarted, processingEnded)
      _                 <-
        info"Processing the response for queryId: $queryId has been completed. (took: ${processingDuration.getSeconds} s)"
    yield ()

  def subscribeToQueryResponses(queryId: QueryId): Stream[IO, Response] =
    Stream.eval(info"Waiting for query completion: $queryId") *>
      pubSub
        .subscribe(queryId)
        .timeout(5.minutes)

object ChatServiceImpl:
  def of()(using
    ChatCompletionService[IO],
    ContextRepository[IO],
    VectorStoreRepository[IO],
    EmbeddingService[IO],
  ): Resource[IO, ChatService[IO]] =
    for
      given Logger[IO] <- Slf4jLogger.create[IO].toResource
      pubSub           <- PubSub.resource[IO]
    yield ChatServiceImpl(pubSub)
