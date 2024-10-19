package supportbot
package rag
package ingestion

import cats.syntax.all.*
import cats.effect.*
import scala.jdk.CollectionConverters.*
import java.io.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.Loader
import smile.nlp.*
import org.apache.pdfbox.text.*

object LocalPDFDocumentLoader:
  def loadPDF(file: File, documentId: DocumentId): IO[Document.Ingested] =
    IO.blocking(fromPDFUnsafe(file, documentId))

  private def fromPDFUnsafe(file: File, documentId: DocumentId): Document.Ingested =
    val document = Loader.loadPDF(file)

    // 1 based index
    val allFragments = (1 to document.getNumberOfPages)
      .foldLeft(Vector.newBuilder[Document.Fragment]): (builder, pageNr) =>
        // this has a couple of issues:
        // 1. it doesn't handle text that spans multiple pages
        // 2. it repeats the document title on every page
        val textStripper = new PDFTextStripper()
        textStripper.setStartPage(pageNr)
        textStripper.setEndPage(pageNr)

        val metadata = Map.empty[String, String]

        builder.addAll:
          textStripper
            .getText(document)
            .sentences
            .toVector
            .mapWithIndex: (sentence, sentenceNr) =>
              Document.Fragment(
                index = pageNr,
                chunk = Chunk(
                  text = sentence,
                  index = sentenceNr,
                  metadata = metadata
                )
              )
      .result()

    Document.Ingested(documentId = documentId, fragments = allFragments)

