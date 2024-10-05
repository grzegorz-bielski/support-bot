package supportbot
package rag

import cats.syntax.all.*
import cats.effect.*
import fs2.Stream
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.client4.*
import sttp.capabilities.fs2.*
import sttp.model.Uri.*
import sttp.openai.OpenAI
import sttp.openai.streaming.fs2.*
import smile.nlp.*
import sttp.openai.requests.embeddings.EmbeddingsRequestBody.*
import sttp.openai.requests.embeddings.EmbeddingsResponseBody.*

trait EmbeddingService[F[_]]:
  def createIndexEmbeddings(document: Document): F[Vector[Embedding.Index]]
  def createQueryEmbeddings(chunk: Chunk): F[Embedding.Query]

final class OpenAIEmbeddingService(
    backend: WebSocketStreamBackend[IO, Fs2Streams[IO]],
    openAIProtocol: OpenAI,
    model: Model
) extends EmbeddingService[IO]:
  val embeddingModel = EmbeddingsModel.CustomEmbeddingsModel(model)

  def createIndexEmbeddings(document: Document) =
    createEmbeddings(
      EmbeddingsInput.MultipleInput(
        document.fragments.map(_.chunk.toEmbeddingInput)
      )
    )
      .map: embeddingResponse =>
        document.fragments
          .zip(embeddingResponse.data)
          .map: (fragment, value) =>
            Embedding.Index(
              chunk = fragment.chunk,
              value = value.embedding.toVector,
              documentId = document.id,
              fragmentIndex = fragment.index
            )

  def createQueryEmbeddings(chunk: Chunk) =
    createEmbeddings(EmbeddingsInput.SingleInput(chunk.toEmbeddingInput))
      .map: response =>
        Embedding.Query(
          // TODO: assuming that single chunk will product one embedding, but we should validate it
          value = response.data.head.embedding.toVector,
          chunk = chunk
        )

  private def createEmbeddings(input: EmbeddingsInput) =
    openAIProtocol
      .createEmbeddings(
        EmbeddingsBody(
          model = embeddingModel,
          input = input
        )
      )
      .send(backend)
      .map(_.body)
      .rethrow
