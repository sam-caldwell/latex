package com.samcaldwell.latex

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class BibliographySetupAction : AnAction("Insert Bibliography Setup") {
  override fun update(e: AnActionEvent) {
    val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE)
    e.presentation.isEnabledAndVisible = vfile?.extension?.lowercase() == TexFileType.defaultExtension
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val doc = editor.document

    val options = arrayOf("APA7", "MLA", "Chicago (Author-Date)")
    val idx = Messages.showDialog(
      project,
      "Choose citation style to configure:",
      "Bibliography Setup",
      options,
      0,
      null
    )
    if (idx < 0) return
    val style = options[idx]

    // Ensure library.bib exists
    project.getService(BibLibraryService::class.java).ensureLibraryExists()

    val insertion = when (style) {
      "APA7" -> listOf(
        "\\usepackage[style=apa,backend=biber]{biblatex}",
        "\\DeclareLanguageMapping{american}{american-apa}",
        "\\addbibresource{library.bib}"
      )
      "MLA" -> listOf(
        "\\usepackage[style=mla,backend=biber]{biblatex}",
        "\\addbibresource{library.bib}"
      )
      else -> listOf(
        "\\usepackage[authordate,backend=biber]{biblatex-chicago}",
        "\\addbibresource{library.bib}"
      )
    }

    WriteCommandAction.runWriteCommandAction(project) {
      insertPreambleLines(project, doc, insertion)
      ensurePrintBibliography(doc)
    }

    Messages.showInfoMessage(project, "Bibliography configured for $style. Use \\parencite{key} or \\textcite{key}.", "Bibliography")
  }

  private fun insertPreambleLines(project: Project, doc: Document, lines: List<String>) {
    val text = doc.charsSequence.toString()
    val beginIndex = text.indexOf("\\begin{document}")
    val insertAt = if (beginIndex >= 0) beginIndex else 0
    val existing = text.substring(0, insertAt)
    val toInsert = lines.filter { !existing.contains(it) }
    if (toInsert.isEmpty()) return
    val block = toInsert.joinToString(separator = "\n", postfix = "\n")
    doc.insertString(insertAt, block)
  }

  private fun ensurePrintBibliography(doc: Document) {
    val text = doc.charsSequence.toString()
    if (text.contains("\\printbibliography")) return
    val endIndex = text.lastIndexOf("\\end{document}")
    val insertAt = if (endIndex >= 0) endIndex else text.length
    val block = "\n\\printbibliography\n"
    doc.insertString(insertAt, block)
  }
}
