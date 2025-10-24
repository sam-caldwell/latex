package com.samcaldwell.latex

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Image
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.SwingUtilities

class PdfPreviewPanel : JPanel() {
  private var images: List<BufferedImage> = emptyList()
  private var scale: Float = 1.25f
  private var lastPath: Path? = null

  fun loadPdf(path: Path) {
    try {
      lastPath = path
      PdfboxCompat.loadFromFile(path.toFile()).use { doc ->
        val renderer = PDFRenderer(doc)
        val rendered = (0 until doc.numberOfPages).map { page ->
          renderer.renderImageWithDPI(page, 96f * scale, ImageType.RGB)
        }
        images = rendered
        revalidatePreferredSize()
        repaint()
      }
    } catch (_: Throwable) {
      images = emptyList()
      revalidatePreferredSize()
      repaint()
    }
  }

  private fun revalidatePreferredSize() {
    val w = images.maxOfOrNull { it.width } ?: 800
    val h = images.sumOf { it.height } + (images.size - 1) * 16
    preferredSize = Dimension(w + 20, h + 20)
    SwingUtilities.invokeLater { revalidate() }
  }

  fun zoomIn() { scale *= 1.1f; lastPath?.let { loadPdf(it) } }
  fun zoomOut() { scale /= 1.1f; lastPath?.let { loadPdf(it) } }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    var y = 10
    for (img in images) {
      g.drawImage(img, 10, y, null)
      y += img.height + 16
    }
  }
}
