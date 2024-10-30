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
  def createIndexEmbeddings(document: Document.Ingested, model: Model): F[Vector[Embedding.Index]]
  def createQueryEmbeddings(chunk: Chunk, model: Model): F[Embedding.Query]

final class SttpOpenAIEmbeddingService(using backend: SttpBackend, openAIProtocol: OpenAI) extends EmbeddingService[IO]:
  def createIndexEmbeddings(document: Document.Ingested, model: Model): IO[Vector[Embedding.Index]] =
    createEmbeddings(
      input = EmbeddingsInput.MultipleInput(
        document.fragments.map(_.chunk.toEmbeddingInput),
      ),
      model = model,
    )
      .map: embeddingResponse =>
        document.fragments
          .zip(embeddingResponse.data)
          .map: (fragment, embeddingData) =>
            Embedding.Index(
              chunk = fragment.chunk,
              value = embeddingData.embeddingValues,
              documentId = document.info.id,
              fragmentIndex = fragment.index,
            )

  def createQueryEmbeddings(chunk: Chunk, model: Model): IO[Embedding.Query] =
    createEmbeddings(
      input = EmbeddingsInput.SingleInput(chunk.toEmbeddingInput),
      model = model,
    )
      .map: response =>
        Embedding.Query(
          // TODO: assuming that single chunk will product one embedding for `SingleInput`, but we should validate it
          value = response.data.head.embeddingValues,
          chunk = chunk,
        )

  private def createEmbeddings(input: EmbeddingsInput, model: Model) =
    openAIProtocol
      .createEmbeddings(
        EmbeddingsBody(
          input = input,
          model = EmbeddingsModel.CustomEmbeddingsModel(model.name),
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
