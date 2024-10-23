package supportbot
package rag
package ingestion

import cats.effect.*
import cats.syntax.all.*
import scala.jdk.CollectionConverters.*
import java.nio.file.Path
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader
import dev.langchain4j.data.document.parser.TextDocumentParser
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser

object LocalLangChain4jIngestion:
  // TODO: this is just a rough estimation that works with currently used models

  // we should really use a model specific tokenizer like in transformers lib: https://huggingface.co/Snowflake/snowflake-arctic-embed-m#using-huggingface-transformers
  // waiting for... https://github.com/ollama/ollama/issues/3582
  // llama.cpp already has the endpoints for it: https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md

  // langchain4j doesn't have a model specific tokenizer yet
  private val minCharsPerToken = 1 // only this is somewhat working
  private val maxCharsPerToken = 5

  def loadPDF(path: Path, maxTokens: Int): IO[Vector[Document.Fragment]] =
    // TODO: each blocking / side effective fragment should be wrapped in IO, not the whole thing
    IO.blocking:
      val documentParser = ApacheTikaDocumentParser()
      val document       = FileSystemDocumentLoader.loadDocument(path, documentParser)

      val maxSegmentSizeInChars = maxTokens * minCharsPerToken
      val maxOverlapSizeInChars = 1 * maxCharsPerToken

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
