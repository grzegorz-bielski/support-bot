package supportbot

enum Embedding:
  case Index(
      chunk: Chunk,
      value: Vector[Double],
      documentId: String,
      fragmentIndex: Int
  )

  case Query(
      chunk: Chunk,
      value: Vector[Double]
  )
