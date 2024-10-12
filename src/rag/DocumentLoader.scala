package supportbot
package rag

import cats.syntax.all.*
import cats.effect.*
import scala.jdk.CollectionConverters.*
import java.io.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.Loader
import smile.nlp.*
import org.apache.pdfbox.text.PDFTextStripper

object DocumentLoader:
  def loadPDF(path: String): IO[Document] =
    IO.blocking(fromPDFUnsafe(path))

  private def fromPDFUnsafe(path: String): Document =
    val file = File(path)
    val fileName = file.getName
    val document = Loader.loadPDF(File(path))

    // 1 based index
    val allFragments = (1 to document.getNumberOfPages)
      .foldLeft(Vector.newBuilder[DocumentFragment]): (builder, i) =>
        val textStripper = new PDFTextStripper()
        textStripper.setStartPage(i)
        textStripper.setEndPage(i)

        val metadata = Map("page" -> i.toString, "file" -> fileName)

        builder.addAll:
          textStripper
            .getText(document)
            .sentences
            .toVector
            .map: value =>
              DocumentFragment(index = i, Chunk(value, metadata))
      .result()

    Document(id = fileName, version = 1, fragments = allFragments)
