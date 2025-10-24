package net.samcaldwell.latex

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JDialog
import javax.swing.JScrollPane

class RenderLatexFenceAction : AnAction("Render LaTeX Code Fence") {
  override fun update(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR)
    val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE)
    var enable = false
    if (editor != null && vfile != null) {
      val name = vfile.name.lowercase()
      if (name.endsWith(".md") || name.endsWith(".markdown")) {
        val caret = editor.caretModel.currentCaret
        val doc = editor.document
        enable = isCaretInLatexFence(doc.charsSequence, caret.offset)
      }
    }
    e.presentation.isEnabledAndVisible = enable
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val doc = editor.document
    val text = doc.charsSequence
    val fence = extractFenceTextAtOffset(text, editor.caretModel.offset) ?: run {
      Messages.showErrorDialog(project, "No LaTeX code fence found at caret.", "LaTeX")
      return
    }

    val tmpDir = Files.createTempDirectory("md-latex-")
    val texPath = tmpDir.resolve("snippet.tex")
    val content = wrapLatexDocument(fence)
    Files.writeString(texPath, content)

    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(texPath.toFile())
    if (vFile == null) {
      Messages.showErrorDialog(project, "Failed to create temporary file.", "LaTeX")
      return
    }
    val service = project.getService(LatexCompilerService::class.java)
    val pdf = service.compile(vFile)
    if (pdf == null || !Files.exists(pdf)) {
      Messages.showErrorDialog(project, "Compilation failed.", "LaTeX")
      return
    }

    ApplicationManager.getApplication().invokeLater {
      val panel = PdfPreviewPanel()
      panel.loadPdf(pdf)
      val dialog = JDialog()
      dialog.title = "LaTeX Preview"
      dialog.contentPane.add(JScrollPane(panel), BorderLayout.CENTER)
      dialog.setSize(900, 700)
      dialog.setLocationRelativeTo(null)
      dialog.isModal = false
      dialog.isVisible = true
    }
  }

  private fun wrapLatexDocument(body: CharSequence): String =
    """
    \documentclass{article}
    \usepackage{amsmath,amssymb}
    \usepackage[utf8]{inputenc}
    \usepackage[T1]{fontenc}
    \begin{document}
    ${body}
    \end{document}
    """.trimIndent()

  private fun isCaretInLatexFence(text: CharSequence, offset: Int): Boolean =
    extractFenceTextAtOffset(text, offset) != null

  private fun extractFenceTextAtOffset(text: CharSequence, offset: Int): String? {
    val len = text.length
    var pos = offset.coerceIn(0, len)
    // Find start of current line
    var lineStart = pos
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

    // Find opening fence upward
    var openLineStart = -1
    var openLineEnd = -1
    var p = lineStart
    while (p >= 0) {
      var prevStart = p - 1
      while (prevStart > 0 && text[prevStart - 1] != '\n') prevStart--
      val line = text.subSequence(prevStart.coerceAtLeast(0), p).toString()
      val trimmed = line.trim()
      if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
        openLineStart = prevStart.coerceAtLeast(0)
        openLineEnd = p
        break
      }
      if (prevStart <= 0) break
      p = prevStart
    }
    if (openLineStart < 0) return null
    val header = text.subSequence(openLineStart, openLineEnd).toString().trim()
    val info = header.removePrefix("```").removePrefix("~~~").trim().lowercase()
    if (info != "latex" && info != "tex") return null

    // Find closing fence downward
    var q = openLineEnd
    if (q < len && text[q] == '\n') q++
    var closeStart = -1
    while (q < len) {
      val nl = text.indexOf('\n', q)
      val end = if (nl == -1) len else nl + 1
      val line = text.subSequence(q, end).toString()
      if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) {
        closeStart = q
        break
      }
      q = end
    }
    if (closeStart < 0) return null

    val contentStart = openLineEnd + (if (openLineEnd < len && text[openLineEnd] == '\n') 1 else 0)
    val contentEnd = closeStart - (if (closeStart > contentStart && text[closeStart - 1] == '\n') 1 else 0)
    if (contentStart > contentEnd || contentStart < 0 || contentEnd > len) return null
    return text.subSequence(contentStart, contentEnd).toString()
  }
}
