package supportbot
package rag
package ingestion

import cats.syntax.all.*
import cats.effect.*
import scala.jdk.CollectionConverters.*
import java.io.*
// import org.apache.pdfbox.pdmodel.PDDocument
// import org.apache.pdfbox.Loader
import smile.nlp.*
// import org.apache.pdfbox.text.*

object LocalPDFDocumentLoader:
  def loadPDF(file: File): IO[Vector[Document.Fragment]] =
    IO.blocking(fromPDFUnsafe(file))

  private def fromPDFUnsafe(file: File): Vector[Document.Fragment] =
    // val document = Loader.loadPDF(file)

    ???

    // // 1 based index
    // (1 to document.getNumberOfPages)
    //   .foldLeft(Vector.newBuilder[Document.Fragment]): (builder, pageNr) =>
    //     // TODO: use langchain4j splitters / tokenizers
    //     // - https://camel.apache.org/components/4.8.x/others/langchain4j-tokenizer.html
    //     // - https://docs.langchain4j.dev/tutorials/rag/#document-splitter
    
    //     // this has a couple of issues:
    //     // 0. THE BIGGEST ISSUE: the sentences might not fit into embedding model context window, which is reducing accuracy will often crash ollama!
    //     // 1. it doesn't handle text that spans multiple pages
    //     // 2. it repeats the document title on every page
    //     val textStripper = new PDFTextStripper()
    //     textStripper.setStartPage(pageNr)
    //     textStripper.setEndPage(pageNr)

    //     val metadata = Map.empty[String, String]

    //     builder.addAll:
    //       textStripper
    //         .getText(document)
    //         .sentences
    //         .toVector
    //         .mapWithIndex: (sentence, sentenceNr) =>
    //           Document.Fragment(
    //             index = pageNr,
    //             chunk = Chunk(
    //               text = sentence,
    //               index = sentenceNr,
    //               metadata = metadata
    //             )
    //           )
    //   .result()

