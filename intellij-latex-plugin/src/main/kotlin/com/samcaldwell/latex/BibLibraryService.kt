package com.samcaldwell.latex

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

@Service(Service.Level.PROJECT)
class BibLibraryService(private val project: Project) {
  private val log = Logger.getInstance(BibLibraryService::class.java)

  fun libraryPath(): Path? {
    val base = project.basePath ?: return null
    return Paths.get(base).resolve("library.bib")
  }

  fun ensureLibraryExists(): Path? {
    val path = libraryPath() ?: return null
    try {
      if (!Files.exists(path)) {
        val header = buildString {
          appendLine("% library.bib - Managed by LaTeX Tools + Preview plugin")
          appendLine("% Created: ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
          appendLine()
        }
        Files.write(path, header.toByteArray(StandardCharsets.UTF_8))
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())?.let { it.refresh(false, false) }
      }
    } catch (t: Throwable) {
      log.warn("Failed to ensure library.bib exists", t)
    }
    return path
  }

  data class BibEntry(
    val type: String,
    val key: String,
    val fields: Map<String, String>
  )

  fun readEntries(): List<BibEntry> {
    val path = ensureLibraryExists() ?: return emptyList()
    return try {
      val text = Files.readString(path)
      val entries = mutableListOf<BibEntry>()
      val entryPattern = Pattern.compile("@([a-zA-Z]+)\\s*\\{\\s*([^,\\s]+)\\s*,([\\s\\S]*?)\\n\\}", Pattern.MULTILINE)
      val m = entryPattern.matcher(text)
      while (m.find()) {
        val type = m.group(1).lowercase()
        val key = m.group(2)
        val body = m.group(3)
        val fields = parseFields(body)
        entries.add(BibEntry(type, key, fields))
      }
      entries
    } catch (t: Throwable) {
      log.warn("Failed to read library.bib entries", t)
      emptyList()
    }
  }

  private fun parseFields(body: String): Map<String, String> {
    val map = linkedMapOf<String, String>()
    val fieldPattern = Pattern.compile("([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(\\{([^}]*)\\}|\"([^\"]*)\")\\s*,?", Pattern.MULTILINE)
    val fm = fieldPattern.matcher(body)
    while (fm.find()) {
      val name = fm.group(1).lowercase()
      val value = fm.group(3) ?: fm.group(4) ?: ""
      map[name] = value.trim()
    }
    return map
  }

  fun upsertEntry(entry: BibEntry): Boolean {
    val path = ensureLibraryExists() ?: return false
    return try {
      val original = Files.readString(path)
      val normalizedType = entry.type.trim().lowercase()
      val key = entry.key.trim()
      val body = buildFieldsBody(entry.fields)
      val newEntryText = "@${normalizedType}{${key},\n${body}\n}\n\n"

      // Regex to find existing entry by type+key, across lines
      val pattern = Pattern.compile("@${Pattern.quote(normalizedType)}\\s*\\{\\s*${Pattern.quote(key)}\\s*,[\\s\\S]*?\\}\n?", Pattern.MULTILINE)
      val matcher = pattern.matcher(original)
      val updated = if (matcher.find()) {
        matcher.replaceFirst(newEntryText)
      } else {
        if (original.endsWith("\n")) original + newEntryText else (original + "\n" + newEntryText)
      }

      if (updated != original) {
        Files.writeString(path, updated, StandardCharsets.UTF_8)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())?.refresh(false, false)
      }
      true
    } catch (t: Throwable) {
      log.warn("Failed to upsert BibTeX entry", t)
      false
    }
  }

  private fun buildFieldsBody(fields: Map<String, String>): String {
    // Order a few common fields first, then the rest alphabetically
    val priority = listOf("author", "title", "year", "journal", "booktitle", "publisher", "doi", "url")
    val orderedKeys = LinkedHashSet<String>()
    orderedKeys.addAll(priority.filter { fields.containsKey(it) })
    orderedKeys.addAll(fields.keys.sorted())
    return orderedKeys.joinToString(separator = ",\n") { k ->
      val v = fields[k]?.trim().orEmpty()
      "  ${k} = {${escapeBraces(v)}}"
    }
  }

  private fun escapeBraces(s: String): String = s.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")

  // --- DOI/URL import ------------------------------------------------------

  fun importFromDoiOrUrl(identifier: String, preferredKey: String? = null): BibEntry? {
    val doi = extractDoi(identifier.trim()) ?: return null
    val bibtex = fetchBibtexForDoi(doi) ?: return null
    val parsed = parseSingleEntry(bibtex) ?: return null
    val entry = if (!preferredKey.isNullOrBlank()) parsed.copy(key = preferredKey) else parsed
    if (upsertEntry(entry)) return entry
    return null
  }

  private fun extractDoi(id: String): String? {
    val trimmed = id.trim()
    if (trimmed.startsWith("10.")) return trimmed
    val lower = trimmed.lowercase()
    val doiPrefix = Regex("https?://(dx\\.)?doi\\.org/(10\\..+)")
    val m = doiPrefix.find(lower) ?: return null
    return m.groupValues[2]
  }

  private fun fetchBibtexForDoi(doi: String): String? {
    return try {
      val url = java.net.URL("https://doi.org/" + doi)
      val conn = url.openConnection() as java.net.HttpURLConnection
      conn.instanceFollowRedirects = true
      conn.setRequestProperty("Accept", "application/x-bibtex; charset=utf-8")
      conn.connectTimeout = 10000
      conn.readTimeout = 15000
      conn.inputStream.use { ins ->
        ins.readBytes().toString(Charsets.UTF_8)
      }
    } catch (_: Throwable) {
      null
    }
  }

  fun parseSingleEntry(bibtex: String): BibEntry? {
    val entryPattern = Pattern.compile("@([a-zA-Z]+)\\s*\\{\\s*([^,\\s]+)\\s*,([\\s\\S]*?)\\n\\}", Pattern.MULTILINE)
    val m = entryPattern.matcher(bibtex)
    if (!m.find()) return null
    val type = m.group(1).lowercase()
    val key = m.group(2)
    val body = m.group(3)
    val fields = parseFields(body)
    return BibEntry(type, key, fields)
  }
}
