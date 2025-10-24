package net.samcaldwell.latex

import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File

object PdfboxCompat {
  fun loadFromBytes(bytes: ByteArray): PDDocument {
    val m = PDDocument::class.java.getMethod("load", ByteArray::class.java)
    return m.invoke(null, bytes) as PDDocument
  }

  fun loadFromFile(file: File): PDDocument {
    val m = PDDocument::class.java.getMethod("load", File::class.java)
    return m.invoke(null, file) as PDDocument
  }
}
