package supportbot
package context
package chat

import fs2.{Chunk as _, *}
import cats.effect.*
import cats.syntax.all.*
import org.typelevel.log4cats.*
import org.typelevel.log4cats.slf4j.*
import org.typelevel.log4cats.syntax.*
import scala.concurrent.duration.{span as _, *}
import java.util.UUID

import supportbot.rag.vectorstore.*
import supportbot.rag.*

final class ChatService(
  pubSub: PubSub[IO],
)(using
  logger: Logger[IO],
  chatCompletionService: ChatCompletionService[IO],
  vectorStore: VectorStoreRepository[IO],
  embeddingService: EmbeddingService[IO],
):
  import ChatService.*

  // TODO:
  // - chat history

  // TODO: do not use xml tags in llama
  // TODO: take prompt from context
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

  def processQuery(
      query: ChatQuery, 
      queryId: QueryId,
      promptTemplate: PromptTemplate,
      retrieveOptions: RetrieveOptions,
    ): IO[Unit] =
    for
      queryEmbeddings     <- embeddingService.createQueryEmbeddings(Chunk(query.content, index = 0))
      retrievedEmbeddings <- vectorStore.retrieve(
        queryEmbeddings, 
        retrieveOptions,
        // RetrieveOptions(
        //   topK = 15, 
        //   fragmentLookupRange = LookupRange(5, 5)
        // ),
      ).compile.toVector

      // _ <- info"Retrieved embeddings: $retrievedEmbeddings"

      topicId = queryId.toString

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
             .chatCompletion(prompt)
             .map: chatMsg =>
               PubSub.Message(
                 topicId = topicId,
                 eventType = ChatEvent.QueryResponse,
                 content = chatMsg.contentDeltas,
               )
             .onComplete:
               Stream(
                 PubSub.Message(
                   topicId = topicId,
                   eventType = ChatEvent.QueryClose,
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

  def subscribeToQueryResponses(queryId: QueryId): Stream[IO, PubSub.Message] =
    Stream.eval(info"Waiting for query completion: $queryId") *>
      pubSub
        .subscribe(queryId.toString)
        .timeout(5.minutes)

object ChatService:
  def of()(using ChatCompletionService[IO], ContextRepository[IO], VectorStoreRepository[IO], EmbeddingService[IO]): Resource[IO, ChatService] =
    for
      given Logger[IO] <- Slf4jLogger.create[IO].toResource
      pubSub           <- PubSub.resource[IO]
    yield ChatService(pubSub)
