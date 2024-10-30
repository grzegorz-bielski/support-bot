package supportbot
package rag
package ingestion

import fs2.{io as _, Chunk as _, *}
import fs2.io.*
import cats.effect.*
import cats.syntax.all.*
import scala.jdk.CollectionConverters.*
import java.nio.file.Path
import dev.langchain4j.data.document.Document as LangChain4JDocument
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader
import dev.langchain4j.data.document.parser.TextDocumentParser
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser

object LangChain4jIngestion:
  // TODO: this is just a rough estimation that works with currently used models

  // we should really use a model specific tokenizer like in transformers lib: https://huggingface.co/Snowflake/snowflake-arctic-embed-m#using-huggingface-transformers
  // waiting for... https://github.com/ollama/ollama/issues/3582
  // llama.cpp already has the endpoints for it: https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md

  // langchain4j doesn't have a model specific tokenizer yet
  private val minCharsPerToken = 1 // only this is somewhat working
  private val maxCharsPerToken = 5

  def loadFrom(stream: Stream[IO, Byte], maxTokens: Int): IO[Vector[Document.Fragment]] =
    toInputStreamResource(stream).use: inputStream =>
      val documentParser = ApacheTikaDocumentParser()

      IO.blocking(documentParser.parse(inputStream))
        .flatMap(processDocument(_, maxTokens))

  def loadPDF(path: Path, maxTokens: Int): IO[Vector[Document.Fragment]] =
    IO.blocking:
      val documentParser = ApacheTikaDocumentParser()
      FileSystemDocumentLoader.loadDocument(path, documentParser)
    .flatMap(processDocument(_, maxTokens))

  private def processDocument(document: LangChain4JDocument, maxTokens: Int): IO[Vector[Document.Fragment]] =
    IO.blocking:
      val maxSegmentSizeInChars = maxTokens * minCharsPerToken
      val maxOverlapSizeInChars = 1 * maxCharsPerToken

      // creating sentence splitter is effective, it loads opennlp binary from the classpath
      val splitter = DocumentSplitters.recursive(
        maxSegmentSizeInChars,
        maxOverlapSizeInChars,
      )

      splitter
        .split(document)
        .asScala
        .toVector
        .mapWithIndex: (textSegment, i) =>
          // there is no clear separation of fragments in the source document using langchain4j parsers,
          // so we set fragment_index = chunk_index and the chunk index to 0
          Document.Fragment(
            index = i,
            chunk = Chunk(
              text = textSegment.text,
              index = 0,
              metadata = Map.empty,
            ),
          )
