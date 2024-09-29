//> using scala 3.5.0
//> using toolkit typelevel:0.1.28
//> using dep com.softwaremill.sttp.openai::fs2:0.2.3
//> using dep com.softwaremill.sttp.openai::core:0.2.3
//> using dep com.softwaremill.sttp.client4::cats:4.0.0-M17
//> using dep com.github.haifengl::smile-scala:3.1.1
//> using dep org.apache.pdfbox:pdfbox:3.0.3

package supportbot

import cats.syntax.all.*
import cats.effect.*

object Main extends IOApp.Simple:
  def run =
    LLMService.resource.use(program)

  def program(llmService: LLMService): IO[Unit] =
    for
      // offline - parsing and indexing
      _ <- IO.println("Chunking PDF")
      document <- DocumentLoader.loadPDF("./resources/SAFE3 - Support Guide-v108-20240809_102738.pdf")
      _ <- IO.println(s"Creating embeddings. It may take a while...")
      indexEmbeddings <- llmService.createIndexEmbeddings(document)
      // TODO: store index embeddings on disk

      query = "How do I solve manual resolution with unresolved_games reason?"
      queryEmbeddings <- llmService.createQueryEmbeddings(Chunk(query))

      // TODO: retrieve embedding according to the similarity function
      contextChunk <- KNN.retrieve(index = indexEmbeddings, query = queryEmbeddings)
      _ <- IO.println(s"Retrieved context: ${contextChunk.map(_.toEmbeddingInput)}")

      - <- IO.println("Asking for chat completion")
      res <- llmService.runChatCompletion(
        query = query,
        context = contextChunk.map(_.toEmbeddingInput).mkString("\n")
      )
    yield ()
