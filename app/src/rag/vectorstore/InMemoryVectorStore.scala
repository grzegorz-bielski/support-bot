package supportbot
package rag
package vectorstore

import cats.syntax.all.*
import cats.effect.*
import fs2.*

final class InMemoryVectorStore(ref: Ref[IO, Vector[Embedding.Index]]) extends VectorStoreRepository[IO]:
  def store(index: Vector[Embedding.Index]): IO[Unit] =
    ref.update(_ ++ index).void

  def retrieve(query: Embedding.Query, options: RetrieveOptions): Stream[IO, Embedding.Retrieved] =
    Stream.eval(ref.get).flatMap(KNN.retrieve(_, query, options.topK))

  def documentEmbeddingsExists(contextId: ContextId, documentId: DocumentId): IO[Boolean] =
    ref.get.map(_.exists(_.documentId == documentId))

  def delete(contextId: ContextId, documentId: DocumentId): IO[Unit] =
    ref.update(_.filterNot(_.documentId == documentId))

  private object KNN:
    def retrieve(
      index: Vector[Embedding.Index], // assuming this comes from all the documents
      query: Embedding.Query,         // to be classified
      topK: Int,
    ): Stream[IO, Embedding.Retrieved] =
      Stream.emits:
        for
          (neighbor, score)  <- findKNearestNeighbors(
                                  data = index.map(embedding => (embedding.value.map(_.toDouble), embedding)),
                                  input = query.value.map(_.toDouble),
                                  k = topK,
                                )
          // neighbor lookup window, like +/- 1 page
          fragmentsIndexRange =
            neighbor.fragmentIndex - 1 to neighbor.fragmentIndex + 1
          // full scan...
          neighboringChunk   <- index.collect:
                                  case embedding: Embedding.Index
                                      if neighbor.documentId == embedding.documentId &&
                                        fragmentsIndexRange.contains(embedding.fragmentIndex) =>
                                    Embedding.Retrieved(
                                      chunk = embedding.chunk,
                                      value = embedding.value,
                                      documentId = embedding.documentId,
                                      contextId = embedding.contextId,
                                      fragmentIndex = embedding.fragmentIndex,
                                      score = score, // score for the main chunk, not the neighboring chunk
                                    )
        yield neighboringChunk

    private def cosineSimilarity(vec1: Vector[Double], vec2: Vector[Double]): Double =
      val magnitude1 = math.sqrt(vec1.map(x => x * x).sum)
      val magnitude2 = math.sqrt(vec2.map(x => x * x).sum)

      dotProduct(vec1, vec2) / (magnitude1 * magnitude2)

    private def dotProduct(u: Vector[Double], v: Vector[Double]): Double =
      (u lazyZip v).foldLeft(0d):
        case (acc, (ui, vi)) => acc + (ui * vi)

    private def findKNearestNeighbors[T](
      data: Vector[(Vector[Double], T)],
      input: Vector[Double],
      k: Int,
    ): Vector[(T, Double)] =
      data
        .map: (features, label) =>
          (label, cosineSimilarity(features, input))
        .sortBy(-_._2)
        .take(k)

object InMemoryVectorStore:
  def of: IO[InMemoryVectorStore] =
    Ref.of[IO, Vector[Embedding.Index]](Vector.empty).map(InMemoryVectorStore(_))
