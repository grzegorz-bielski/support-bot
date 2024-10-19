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
  def createIndexEmbeddings(document: Document.Ingested): F[Vector[Embedding.Index]]
  def createQueryEmbeddings(chunk: Chunk): F[Embedding.Query]

final class SttpOpenAIEmbeddingService(model: Model)(using backend: SttpBackend, openAIProtocol: OpenAI)
    extends EmbeddingService[IO]:
  val embeddingModel = EmbeddingsModel.CustomEmbeddingsModel(model)

  def createIndexEmbeddings(document: Document.Ingested): IO[Vector[Embedding.Index]] =
    createEmbeddings(
      EmbeddingsInput.MultipleInput(
        document.fragments.map(_.chunk.toEmbeddingInput),
      ),
    )
      .map: embeddingResponse =>
        document.fragments
          .zip(embeddingResponse.data)
          .map: (fragment, embeddingData) =>
            Embedding.Index(
              chunk = fragment.chunk,
              value = embeddingData.embeddingValues,
              documentId = document.documentId,
              fragmentIndex = fragment.index,
            )

  def createQueryEmbeddings(chunk: Chunk): IO[Embedding.Query] =
    createEmbeddings(EmbeddingsInput.SingleInput(chunk.toEmbeddingInput))
      .map: response =>
        Embedding.Query(
          // TODO: assuming that single chunk will product one embedding for `SingleInput`, but we should validate it
          value = response.data.head.embeddingValues,
          chunk = chunk,
        )

  private def createEmbeddings(input: EmbeddingsInput) =
    openAIProtocol
      .createEmbeddings(
        EmbeddingsBody(
          model = embeddingModel,
          input = input,
        ),
      )
      .send(backend)
      .map(_.body)
      .rethrow

  extension (underlying: EmbeddingData)
    def embeddingValues: Vector[Float] =
      // assuming the model returns embedding vectors in float32
      // this is usually true, but it's model-specific
      underlying.embedding.toVector.map(_.toFloat)
