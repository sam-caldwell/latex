package com.samcaldwell.latex

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

object AiBibliographyLookup {
  private val possiblePluginIds = listOf(
    "com.intellij.ml.llm",
    "com.jetbrains.ai",
    "org.jetbrains.ai"
  )

  fun isAiAvailable(): Boolean =
    possiblePluginIds.any { id -> PluginManagerCore.isPluginInstalled(PluginId.getId(id)) }

  // Best-effort: attempt to use JetBrains AI Assistant if a public API is present.
  // Falls back to null (caller will use Crossref/DOI logic).
  fun lookup(project: Project, identifier: String): BibLibraryService.BibEntry? {
    // Try reflective integration with a hypothetical Chat API if present in future versions.
    // This block is designed to be forward-compatible and safe if classes are absent.
    return try {
      val clsNameCandidates = listOf(
        // Hypothetical fully-qualified class names for a Chat client
        "com.intellij.ml.llm.client.ChatClient",
        "com.intellij.ml.llm.api.ChatService"
      )
      val (cls, method) = clsNameCandidates.asSequence().mapNotNull { name ->
        try {
          val c = Class.forName(name)
          val m = c.methods.firstOrNull { it.name.equals("query", true) || it.name.equals("send", true) }
          if (m != null) c to m else null
        } catch (_: Throwable) { null }
      }.firstOrNull() ?: return null

      val client = cls.getDeclaredConstructor().newInstance()
      val prompt = buildPrompt(identifier)
      val response = method.invoke(client, prompt) as? String ?: return null
      parseResponseToEntry(response)
    } catch (_: Throwable) {
      null
    }
  }

  private fun buildPrompt(identifier: String): String =
    """
    You are a bibliography assistant. Given an identifier which can be an ISBN or DOI, return a valid BibTeX entry.
    - Output only a single BibTeX entry block (no extra commentary).
    - Ensure fields include author, title, year, and publisher/journal as appropriate.

    Identifier: $identifier
    """.trimIndent()

  private fun parseResponseToEntry(text: String): BibLibraryService.BibEntry? {
    // Reuse existing BibTeX parser; expects a single entry
    val svc = com.intellij.openapi.application.ApplicationManager.getApplication().getService(BibLibraryService::class.java)
    return svc.parseSingleEntry(text)
  }
}

