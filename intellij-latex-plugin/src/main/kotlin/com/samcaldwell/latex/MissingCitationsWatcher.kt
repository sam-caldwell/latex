package com.samcaldwell.latex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.AppTopics
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.ui.Messages
import java.util.regex.Pattern

class MissingCitationsWatcher : StartupActivity.DumbAware {
  override fun runActivity(project: Project) {
    val bus = project.messageBus.connect(project)
    bus.subscribe(AppTopics.FILE_DOCUMENT_SYNC, object : FileDocumentManagerListener {
      override fun beforeDocumentSaving(document: Document) {
        val vf = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vf.extension?.lowercase() != TexFileType.defaultExtension) return
        val text = document.charsSequence.toString()
        val used = findCiteKeys(text)
        if (used.isEmpty()) return
        val libKeys = project.getService(BibLibraryService::class.java).readEntries().map { it.key }.toSet()
        val missing = used.filterNot { libKeys.contains(it) }
        if (missing.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
          val list = missing.joinToString(", ")
          val choice = Messages.showYesNoCancelDialog(
            project,
            "Missing bibliography entries: $list\nCreate stubs or import by DOI? You will be prompted per key.",
            "Missing Citations",
            "Resolve",
            "Skip",
            "Cancel",
            null
          )
          if (choice == Messages.YES) {
            for (key in missing) {
              resolveKey(project, key)
            }
          }
        }
      }
    })
  }

  private fun resolveKey(project: Project, key: String) {
    val id = Messages.showInputDialog(
      project,
      "Enter DOI, URL, title, or PDF URL for '$key' (leave blank to create stub):",
      "Resolve Citation",
      null
    )
    val svc = project.getService(BibLibraryService::class.java)
    if (!id.isNullOrBlank()) {
      val imported = svc.importFromAny(id.trim(), preferredKey = key)
      if (imported != null) return
      Messages.showErrorDialog(project, "Import failed for '$key'. Creating stub.", "Bibliography")
    }
    // Create stub misc entry
    svc.upsertEntry(BibLibraryService.BibEntry("misc", key, emptyMap()))
  }

  private fun findCiteKeys(text: String): Set<String> {
    val keys = mutableSetOf<String>()
    // Matches \cite{key1,key2}, \parencite[opt]{key}, \textcite{key}
    val pattern = Pattern.compile("\\\\(textcite|parencite|cite|autocite|footcite|citet|citep)\\s*(?:\\[[^]]*\\]\\s*)?\\{([^}]*)\\}")
    val m = pattern.matcher(text)
    while (m.find()) {
      val group = m.group(2)
      group.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { keys.add(it) }
    }
    return keys
  }
}
