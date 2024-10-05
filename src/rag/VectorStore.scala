package supportbot
package rag

import cats.syntax.all.*
import cats.effect.*

trait VectorStore[F[_]]:
  def store(index: Vector[Embedding.Index]): F[Unit]
  def retrieve(query: Embedding.Query): F[Vector[Chunk]]

final class InMemoryVectorStore(ref: Ref[IO, Vector[Embedding.Index]]) extends VectorStore[IO]:
  def store(index: Vector[Embedding.Index]): IO[Unit] =
    ref.update(_ ++ index).void

  def retrieve(query: Embedding.Query): IO[Vector[Chunk]] =
    ref.get.flatMap: index =>
      KNN.retrieve(index = index, query = query)

  private object KNN:
    def retrieve(
        index: Vector[Embedding.Index], // assuming this comes from all the documents
        query: Embedding.Query // to be classified
    ): IO[Vector[Chunk]] =
      IO.pure:
        for
          (neighbor, _) <- findKNearestNeighbors(
            data = index.map(embedding => (embedding.value, embedding)),
            input = query.value,
            k = 3
          )
          // neighbor lookup window, like +/- 1 page
          fragmentsIndexRange =
            neighbor.fragmentIndex - 1 to neighbor.fragmentIndex + 1
          // full scan...
          neighboringChunk <- index.collect:
            case Embedding.Index(chunk, _, documentId, fragmentIndex)
                if neighbor.documentId == documentId && fragmentsIndexRange.contains(fragmentIndex) =>
              chunk
        yield neighboringChunk

    // https://en.wikipedia.org/wiki/Cosine_similarity
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
        k: Int
    ): Vector[(T, Double)] =
      data
        .map: (features, label) =>
          (label, cosineSimilarity(features, input)) // TODO: move to Ordering in custom `compare`
        .sortBy(-_._2)
        .take(k)

object InMemoryVectorStore:
  def of: IO[InMemoryVectorStore] =
    Ref.of[IO, Vector[Embedding.Index]](Vector.empty).map(InMemoryVectorStore(_))
