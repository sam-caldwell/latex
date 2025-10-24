package com.samcaldwell.latex

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import java.io.ByteArrayInputStream

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

      // Regex to find existing entry by type+key, across lines
      val pattern = Pattern.compile("@${Pattern.quote(normalizedType)}\\s*\\{\\s*${Pattern.quote(key)}\\s*,[\\s\\S]*?\\}\n?", Pattern.MULTILINE)
      val matcher = pattern.matcher(original)
      val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      val updated = if (matcher.find()) {
        // Update existing: preserve created if present; always update modified
        val matchText = matcher.group()
        val existingCreated = extractFieldValue(matchText, "created")
        val fields = entry.fields.toMutableMap()
        fields["modified"] = now
        if (!existingCreated.isNullOrBlank()) {
          fields["created"] = existingCreated
        } else {
          fields.putIfAbsent("created", now)
        }
        val body = buildFieldsBody(fields)
        val newEntryText = "@${normalizedType}{${key},\n${body}\n}\n\n"
        matcher.replaceFirst(newEntryText)
      } else {
        // New entry: set created and modified to now (override if present)
        val fields = entry.fields.toMutableMap()
        fields["created"] = now
        fields["modified"] = now
        val body = buildFieldsBody(fields)
        val newEntryText = "@${normalizedType}{${key},\n${body}\n}\n\n"
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

  private fun extractFieldValue(entryText: String, fieldName: String): String? {
    return try {
      val p = Pattern.compile("(?i)${Pattern.quote(fieldName)}\\s*=\\s*(\\{([^}]*)\\}|\"([^\"]*)\")")
      val m = p.matcher(entryText)
      if (m.find()) m.group(2) ?: m.group(3) else null
    } catch (_: Throwable) { null }
  }

  private fun buildFieldsBody(fields: Map<String, String>): String {
    // Order a few common fields first, then the rest alphabetically
    val priority = listOf(
      "author", "title", "year", "journal", "booktitle", "publisher", "doi", "url",
      "source", "verified", "created", "modified"
    )
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
    // ensure source field reflects doi.org
    val withSource = parsed.copy(fields = parsed.fields + mapOf("source" to "automated (doi.org)", "verified" to "false"))
    val entry = if (!preferredKey.isNullOrBlank()) withSource.copy(key = preferredKey) else withSource
    if (upsertEntry(entry)) return entry
    return null
  }

  fun importFromAny(input: String, preferredKey: String? = null): BibEntry? {
    val trimmed = input.trim()
    // 1) DOI directly → doi.org BibTeX
    extractDoi(trimmed)?.let { doi ->
      return importFromDoiOrUrl(doi, preferredKey)
    }
    // 2) URL → attempt to extract DOI from page content first
    if (looksLikeUrl(trimmed)) {
      // 2a) If looks like a PDF URL, try parsing PDF for DOI/metadata
      if (isLikelyPdfUrl(trimmed)) {
        importFromPdfUrl(trimmed, preferredKey)?.let { return it }
      }
      val doi = extractDoiFromUrl(trimmed)
      if (doi != null) return importFromDoiOrUrl(doi, preferredKey)
      // 2b) As a fallback, try to treat the URL as a PDF even if it doesn't end with .pdf
      importFromPdfUrl(trimmed, preferredKey)?.let { return it }
    }

    // 3) ISBN or Title lookup through a cascade of sources
    val isbn = extractIsbn(trimmed)
    val settings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(LookupSettingsService::class.java)
    if (isbn != null) {
      val providers = settings.providersForQuery(true)
      for (p in providers) {
        val entry = when (p) {
          LookupSettingsService.PROVIDER_OPENLIBRARY -> tryOpenLibraryByIsbn(isbn)
          LookupSettingsService.PROVIDER_GOOGLEBOOKS -> tryGoogleBooksByIsbn(isbn)
          LookupSettingsService.PROVIDER_CROSSREF -> tryCrossrefByIsbn(isbn)
          LookupSettingsService.PROVIDER_WORLDCAT -> tryWorldCatByIsbn(isbn)
          LookupSettingsService.PROVIDER_BNB -> tryBnbByIsbn(isbn)
          LookupSettingsService.PROVIDER_OPENBD -> tryOpenBdByIsbn(isbn)
          LookupSettingsService.PROVIDER_LOC -> tryLocByIsbn(isbn)
          else -> null
        }
        if (entry != null) return withPreferredKey(entry, preferredKey)
      }
      return null
    }

    val providers = settings.providersForQuery(false)
    for (p in providers) {
      when (p) {
        LookupSettingsService.PROVIDER_OPENLIBRARY -> tryOpenLibraryByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
        LookupSettingsService.PROVIDER_GOOGLEBOOKS -> tryGoogleBooksByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
        LookupSettingsService.PROVIDER_CROSSREF -> crossrefFindDoiByTitle(trimmed)?.let { doi -> importFromDoiOrUrl(doi, preferredKey)?.let { return it } }
        LookupSettingsService.PROVIDER_WORLDCAT -> tryWorldCatByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
        LookupSettingsService.PROVIDER_BNB -> tryBnbByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
        LookupSettingsService.PROVIDER_LOC -> tryLocByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
      }
    }

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

  private fun looksLikeUrl(s: String): Boolean = s.startsWith("http://") || s.startsWith("https://")

  private fun isLikelyPdfUrl(url: String): Boolean =
    Regex("(?i)\\.pdf(\\?.*)?(#.*)?$").containsMatchIn(url)

  private fun extractDoiFromUrl(url: String): String? {
    val content = HttpUtil.get(url) ?: return null
    val patterns = listOf(
      Pattern.compile("https?://doi\\.org/(10\\.[^\\\\\"'<>\\\\s]+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("doi:?(10\\.[^\\\\\"'<>\\\\s]+)", Pattern.CASE_INSENSITIVE)
    )
    for (p in patterns) {
      val m = p.matcher(content)
      if (m.find()) return m.group(1)
    }
    return null
  }

  private fun crossrefFindDoiByTitle(title: String): String? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val json = HttpUtil.get("https://api.crossref.org/works?rows=1&query.bibliographic=$q", accept = "application/json", aliases = arrayOf("crossref", "api.crossref.org"))
      ?: return null
    return try {
      val root = JsonParser.parseString(json).asJsonObject
      val items = root.getAsJsonObject("message")?.getAsJsonArray("items")
      if (items == null || items.size() == 0) null else items[0].asJsonObject.get("DOI")?.asString
    } catch (_: Throwable) { null }
  }

  private fun fetchBibtexForDoi(doi: String): String? =
    HttpUtil.get("https://doi.org/$doi", accept = "application/x-bibtex; charset=utf-8", aliases = arrayOf("doi", "doi.org"))

  // ----- PDF import --------------------------------------------------------

  fun importFromPdfUrl(url: String, preferredKey: String? = null): BibEntry? {
    return try {
      val bytes = HttpUtil.getBytes(url, accept = "application/pdf", aliases = arrayOf("pdf", java.net.URL(url).host)) ?: return null
      org.apache.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
        // Try XMP / document info first
        val info = doc.documentInformation
        val xmp = tryReadXmp(doc)

        // DOI from XMP or text
        val doi = xmp?.let { findDoi(it) } ?: findDoi(extractText(doc, 1, 2))
        if (!doi.isNullOrBlank()) {
          importFromDoiOrUrl(doi, preferredKey)?.let { return it }
        }

        // Build a minimal entry from metadata + first pages
        val text = extractText(doc, 1, 2)
        val fields = linkedMapOf<String, String>()
        val title = xmp?.let { findXmpTitle(it) } ?: info.title ?: guessTitle(text)
        val authors = xmp?.let { findXmpCreators(it) } ?: parseAuthors(info.author)
        val year = info.creationDate?.let { it.get(java.util.Calendar.YEAR).toString() } ?: safeYearFrom(text)
        if (!authors.isNullOrBlank()) fields["author"] = authors
        if (!title.isNullOrBlank()) fields["title"] = title
        if (!year.isNullOrBlank()) fields["year"] = year
        fields["url"] = url
        val type = if (text.contains("Proceedings", true) || text.contains("conference", true)) "inproceedings" else "article"
        val entry = makeBibEntry(type, fields, sourceTag = "pdf")
        return withPreferredKey(entry, preferredKey)
      }
    } catch (_: Throwable) { null }
  }

  private fun tryReadXmp(doc: org.apache.pdfbox.pdmodel.PDDocument): String? {
    return try {
      val md = doc.documentCatalog.metadata ?: return null
      md.createInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Throwable) { null }
  }

  private fun extractText(doc: org.apache.pdfbox.pdmodel.PDDocument, start: Int, end: Int): String {
    return try {
      val stripper = org.apache.pdfbox.text.PDFTextStripper()
      stripper.startPage = start
      stripper.endPage = kotlin.math.min(end, doc.numberOfPages)
      stripper.getText(doc)
    } catch (_: Throwable) { "" }
  }

  private fun findDoi(text: String): String? {
    val patterns = listOf(
      Pattern.compile("https?://doi\\.org/(10\\.[^\\\\\"'<>\\\\s]+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("doi:?(10\\.[^\\\\\"'<>\\\\s]+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("(10\\.[^\\s]+/[^\\s]+)")
    )
    for (p in patterns) {
      val m = p.matcher(text)
      if (m.find()) return m.group(1)
    }
    return null
  }

  private fun findXmpTitle(xmp: String): String? {
    val m1 = Pattern.compile("<dc:title>[\\s\\S]*?<rdf:li[^>]*>([\\s\\S]*?)</rdf:li>", Pattern.CASE_INSENSITIVE).matcher(xmp)
    if (m1.find()) return m1.group(1).trim()
    val m2 = Pattern.compile("<dc:title>([\\s\\S]*?)</dc:title>", Pattern.CASE_INSENSITIVE).matcher(xmp)
    return if (m2.find()) m2.group(1).trim() else null
  }

  private fun findXmpCreators(xmp: String): String? {
    val creatorsBlock = Pattern.compile("<dc:creator>[\\s\\S]*?</dc:creator>", Pattern.CASE_INSENSITIVE).matcher(xmp).let { m -> if (m.find()) m.group() else null } ?: return null
    val items = Pattern.compile("<rdf:li[^>]*>([\\s\\S]*?)</rdf:li>", Pattern.CASE_INSENSITIVE).matcher(creatorsBlock)
    val list = mutableListOf<String>()
    while (items.find()) list += items.group(1).trim()
    return joinAuthors(list)
  }

  private fun guessTitle(text: String): String? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    for (ln in lines.take(30)) {
      val lower = ln.lowercase()
      if (lower.startsWith("abstract") || lower.startsWith("doi:")) continue
      if (ln.length >= 15 && ln.split(" ").size >= 3) return ln
    }
    return null
  }

  private fun parseAuthors(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    // Split on common separators and rebuild with BibTeX 'and'
    val parts = raw.split(';', ',', '&').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size <= 1) return raw
    return parts.joinToString(" and ")
  }

  // ----- Helpers for multi-source ISBN/Title resolution --------------------

  private fun withPreferredKey(entry: BibEntry, preferredKey: String?): BibEntry? {
    val e = if (!preferredKey.isNullOrBlank()) entry.copy(key = preferredKey) else entry
    return if (upsertEntry(e)) e else null
  }

  private fun extractIsbn(s: String): String? {
    val raw = s.uppercase().replace("[^0-9X]".toRegex(), "")
    return when (raw.length) {
      10, 13 -> raw
      else -> null
    }
  }

  private fun safeYearFrom(any: String?): String? {
    if (any == null) return null
    val m = Pattern.compile("(19|20)\\d{2}").matcher(any)
    return if (m.find()) m.group() else null
  }

  private fun canonicalizeKeyBase(author: String?, title: String?, year: String?): String {
    val a = (author ?: "").trim().split(" and ", ",", "&").firstOrNull()?.trim().orEmpty()
    val alast = a.split(" ").lastOrNull()?.lowercase()?.filter { it.isLetter() } ?: "ref"
    val t = (title ?: "").lowercase().replace("[^a-z0-9]".toRegex(), " ").trim().split(" ").filter { it.length >= 3 }.take(3).joinToString("")
    val y = (year ?: "").takeIf { it.matches("\\d{4}".toRegex()) } ?: "noyr"
    return listOf(alast, y, t).filter { it.isNotBlank() }.joinToString("")
  }

  private fun makeBibEntry(defaultType: String, fields: Map<String, String>, keyHint: String? = null, sourceTag: String? = null): BibEntry {
    val type = fields["entrytype"] ?: defaultType
    val author = fields["author"]
    val title = fields["title"]
    val year = fields["year"]
    val base = keyHint ?: canonicalizeKeyBase(author, title, year)
    val key = suggestDuplicateKey(base.ifBlank { "ref" })
    val finalFields = fields.toMutableMap().apply {
      remove("entrytype")
      if (!sourceTag.isNullOrBlank()) this["source"] = "automated ($sourceTag)"
      if (!this.containsKey("verified")) this["verified"] = "false"
    }
    return BibEntry(type, key, finalFields)
  }

  private fun joinAuthors(list: List<String>): String? =
    list.map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" and ")

  private fun tryOpenLibraryByIsbn(isbn: String): BibEntry? {
    val url = "https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"
    val json = HttpUtil.get(url, accept = "application/json", aliases = arrayOf("openlibrary", "openlibrary.org")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val node = root.getAsJsonObject("ISBN:$isbn") ?: return null
    val title = node.get("title")?.asString
    if (title.isNullOrBlank()) return null
    val publishers = mutableListOf<String>()
    val pubsEl = node.get("publishers")
    if (pubsEl != null && pubsEl.isJsonArray) {
      for (el in pubsEl.asJsonArray) {
        val v = if (el.isJsonObject) el.asJsonObject.get("name")?.asString else el.asString
        if (!v.isNullOrBlank()) publishers += v
      }
    }
    val authorNames = mutableListOf<String>()
    val authorsEl = node.get("authors")
    if (authorsEl != null && authorsEl.isJsonArray) {
      for (el in authorsEl.asJsonArray) {
        val name = if (el.isJsonObject) el.asJsonObject.get("name")?.asString else el.asString
        if (!name.isNullOrBlank()) authorNames += name
      }
    }
    val date = node.get("publish_date")?.asString
    val year = safeYearFrom(date)
    val f = linkedMapOf<String, String>()
    joinAuthors(authorNames)?.let { f["author"] = it }
    f["title"] = title
    year?.let { f["year"] = it }
    if (publishers.isNotEmpty()) f["publisher"] = publishers.first()
    f["isbn"] = isbn
    node.get("url")?.asString?.let { f["url"] = it }
    return makeBibEntry("book", f, sourceTag = "openlibrary.org")
  }

  private fun tryOpenLibraryByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val json = HttpUtil.get("https://openlibrary.org/search.json?limit=1&title=$q", accept = "application/json", aliases = arrayOf("openlibrary", "openlibrary.org")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val docs = root.getAsJsonArray("docs") ?: return null
    if (docs.size() == 0) return null
    val d0 = docs[0].asJsonObject
    val titleFound = d0.get("title")?.asString ?: d0.get("title_suggest")?.asString
    if (titleFound.isNullOrBlank()) return null
    val authors = mutableListOf<String>()
    d0.getAsJsonArray("author_name")?.forEach { el -> el.asString?.let { authors += it } }
    val year = d0.get("first_publish_year")?.asString ?: d0.get("publish_year")?.asString
    val isbn = d0.getAsJsonArray("isbn")?.firstOrNull()?.asString
    // If ISBN surfaced, prefer proceeding to DOI via other services later; we can still build a book entry now.
    val f = linkedMapOf<String, String>()
    joinAuthors(authors)?.let { f["author"] = it }
    f["title"] = titleFound
    safeYearFrom(year)?.let { f["year"] = it }
    if (!isbn.isNullOrBlank()) f["isbn"] = isbn
    return makeBibEntry("book", f, sourceTag = "openlibrary.org")
  }

  private fun tryGoogleBooksByIsbn(isbn: String): BibEntry? {
    val json = HttpUtil.get("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn&maxResults=1", accept = "application/json", aliases = arrayOf("googlebooks", "books.googleapis.com", "www.googleapis.com")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val items = root.getAsJsonArray("items") ?: return null
    if (items.size() == 0) return null
    val vi = items[0].asJsonObject.getAsJsonObject("volumeInfo") ?: return null
    val title = vi.get("title")?.asString ?: return null
    val authors = mutableListOf<String>()
    vi.getAsJsonArray("authors")?.forEach { el -> el.asString?.let { authors += it } }
    val publisher = vi.get("publisher")?.asString
    val date = vi.get("publishedDate")?.asString
    val year = safeYearFrom(date)
    val f = linkedMapOf<String, String>()
    joinAuthors(authors)?.let { f["author"] = it }
    f["title"] = title
    year?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    f["isbn"] = isbn
    return makeBibEntry("book", f, sourceTag = "books.googleapis.com")
  }

  private fun tryGoogleBooksByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val json = HttpUtil.get("https://www.googleapis.com/books/v1/volumes?q=intitle:$q&maxResults=1", accept = "application/json", aliases = arrayOf("googlebooks", "books.googleapis.com", "www.googleapis.com")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val items = root.getAsJsonArray("items") ?: return null
    if (items.size() == 0) return null
    val vi = items[0].asJsonObject.getAsJsonObject("volumeInfo") ?: return null
    val titleFound = vi.get("title")?.asString ?: return null
    val authors = mutableListOf<String>()
    vi.getAsJsonArray("authors")?.forEach { el -> el.asString?.let { authors += it } }
    val publisher = vi.get("publisher")?.asString
    val date = vi.get("publishedDate")?.asString
    val year = safeYearFrom(date)
    val f = linkedMapOf<String, String>()
    joinAuthors(authors)?.let { f["author"] = it }
    f["title"] = titleFound
    year?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    return makeBibEntry("book", f, sourceTag = "books.googleapis.com")
  }

  private fun tryCrossrefByIsbn(isbn: String): BibEntry? {
    val q = java.net.URLEncoder.encode(isbn, "UTF-8")
    val json = HttpUtil.get("https://api.crossref.org/works?rows=1&query.bibliographic=$q", accept = "application/json", aliases = arrayOf("crossref", "api.crossref.org")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val message = root.getAsJsonObject("message") ?: return null
    val items = message.getAsJsonArray("items") ?: return null
    if (items.size() == 0) return null
    val item = items[0].asJsonObject
    val doi = item.get("DOI")?.asString
    if (!doi.isNullOrBlank()) {
      val bib = fetchBibtexForDoi(doi)
      if (!bib.isNullOrBlank()) return parseSingleEntry(bib)
    }
    val titleArr = item.getAsJsonArray("title")
    val title = titleArr?.firstOrNull()?.asString
    if (!title.isNullOrBlank()) {
      val dateParts = item.getAsJsonObject("issued")?.getAsJsonArray("date-parts")
      val year = try {
        dateParts?.firstOrNull()?.asJsonArray?.firstOrNull()?.asInt?.toString()
      } catch (_: Throwable) { null }
      val f = linkedMapOf<String, String>()
      f["title"] = title
      year?.let { f["year"] = it }
      f["isbn"] = isbn
      if (!doi.isNullOrBlank()) f["doi"] = doi
      return makeBibEntry("book", f, sourceTag = "api.crossref.org")
    }
    return null
  }

  private fun tryWorldCatByIsbn(isbn: String): BibEntry? {
    val url = "https://classify.oclc.org/classify2/Classify?isbn=$isbn&summary=true"
    val xml = HttpUtil.get(url, accept = "application/xml", aliases = arrayOf("oclc", "worldcat", "classify.oclc.org")) ?: return null
    val parsed = parseWorldCatXml(xml) ?: return null
    val title = parsed["title"] ?: return null
    val author = parsed["author"]
    val year = parsed["year"]
    val f = linkedMapOf<String, String>()
    if (!author.isNullOrBlank()) f["author"] = author
    f["title"] = title
    if (!year.isNullOrBlank()) f["year"] = year
    f["isbn"] = isbn
    return makeBibEntry("book", f, sourceTag = "classify.oclc.org")
  }

  private fun tryWorldCatByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val xml = HttpUtil.get("https://classify.oclc.org/classify2/Classify?title=$q&summary=true", accept = "application/xml", aliases = arrayOf("oclc", "worldcat", "classify.oclc.org")) ?: return null
    val m = parseWorldCatXml(xml) ?: return null
    val f = linkedMapOf<String, String>()
    val author = m["author"]
    if (!author.isNullOrBlank()) f["author"] = author
    f["title"] = m["title"] ?: return null
    m["year"]?.let { f["year"] = it }
    return makeBibEntry("book", f, sourceTag = "classify.oclc.org")
  }

  private fun tryBnbByIsbn(isbn: String): BibEntry? {
    val query = """
      SELECT ?title ?creator ?date WHERE {
        ?s <http://purl.org/ontology/bibo/isbn13> "$isbn" .
        OPTIONAL { ?s <http://purl.org/dc/terms/title> ?title }
        OPTIONAL { ?s <http://purl.org/dc/terms/creator> ?c . ?c <http://xmlns.com/foaf/0.1/name> ?creator }
        OPTIONAL { ?s <http://purl.org/dc/terms/date> ?date }
      } LIMIT 1
    """.trimIndent()
    val q = java.net.URLEncoder.encode(query, "UTF-8")
    val json = HttpUtil.get("https://bnb.data.bl.uk/sparql?query=$q", accept = "application/sparql-results+json", aliases = arrayOf("bnb", "bnb.data.bl.uk")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val bindings = root.getAsJsonObject("results")?.getAsJsonArray("bindings") ?: return null
    if (bindings.size() == 0) return null
    val b0 = bindings[0].asJsonObject
    val title = b0.getAsJsonObject("title")?.get("value")?.asString
    if (title.isNullOrBlank()) return null
    val creator = b0.getAsJsonObject("creator")?.get("value")?.asString
    val date = b0.getAsJsonObject("date")?.get("value")?.asString
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = title
    safeYearFrom(date)?.let { f["year"] = it }
    f["isbn"] = isbn
    return makeBibEntry("book", f, sourceTag = "bnb.data.bl.uk")
  }

  private fun tryBnbByTitle(title: String): BibEntry? {
    val sparql = """
      SELECT ?title ?creator ?date WHERE {
        ?s a <http://purl.org/ontology/bibo/Book> ;
           <http://purl.org/dc/terms/title> ?title .
        FILTER (CONTAINS(LCASE(?title), \"${title.lowercase()}\"))
        OPTIONAL { ?s <http://purl.org/dc/terms/creator> ?c . ?c <http://xmlns.com/foaf/0.1/name> ?creator }
        OPTIONAL { ?s <http://purl.org/dc/terms/date> ?date }
      } LIMIT 1
    """.trimIndent()
    val q = java.net.URLEncoder.encode(sparql, "UTF-8")
    val json = HttpUtil.get("https://bnb.data.bl.uk/sparql?query=$q", accept = "application/sparql-results+json", aliases = arrayOf("bnb", "bnb.data.bl.uk")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val bindings = root.getAsJsonObject("results")?.getAsJsonArray("bindings") ?: return null
    if (bindings.size() == 0) return null
    val b0 = bindings[0].asJsonObject
    val titleFound = b0.getAsJsonObject("title")?.get("value")?.asString
    if (titleFound.isNullOrBlank()) return null
    val creator = b0.getAsJsonObject("creator")?.get("value")?.asString
    val date = b0.getAsJsonObject("date")?.get("value")?.asString
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = titleFound
    safeYearFrom(date)?.let { f["year"] = it }
    return makeBibEntry("book", f, sourceTag = "bnb.data.bl.uk")
  }

  private fun tryOpenBdByIsbn(isbn: String): BibEntry? {
    val json = HttpUtil.get("https://api.openbd.jp/v1/get?isbn=$isbn", accept = "application/json", aliases = arrayOf("openbd", "openbd.jp")) ?: return null
    val arr = JsonParser.parseString(json).asJsonArray
    if (arr.size() == 0 || arr[0].isJsonNull) return null
    val root = arr[0].asJsonObject
    val summary = root.getAsJsonObject("summary") ?: return null
    val title = summary.get("title")?.asString ?: return null
    val author = summary.get("author")?.asString
    val publisher = summary.get("publisher")?.asString
    val pubdate = summary.get("pubdate")?.asString
    val year = safeYearFrom(pubdate)
    val f = linkedMapOf<String, String>()
    if (!author.isNullOrBlank()) f["author"] = author
    f["title"] = title
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    if (!year.isNullOrBlank()) f["year"] = year
    f["isbn"] = isbn
    return makeBibEntry("book", f, sourceTag = "api.openbd.jp")
  }

  private fun tryLocByIsbn(isbn: String): BibEntry? {
    val json = HttpUtil.get("https://www.loc.gov/books/?q=isbn:$isbn&fo=json", accept = "application/json", aliases = arrayOf("loc", "loc.gov", "libraryofcongress")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val results = root.getAsJsonArray("results") ?: return null
    if (results.size() == 0) return null
    val item = results[0].asJsonObject
    val title = item.get("title")?.asString ?: return null
    val creator = item.get("creator")?.asString
    val publisher = item.get("publisher")?.asString
    val date = item.get("date")?.asString
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = title
    safeYearFrom(date)?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    f["isbn"] = isbn
    return makeBibEntry("book", f, sourceTag = "loc.gov")
  }

  private fun tryLocByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val json = HttpUtil.get("https://www.loc.gov/books/?q=$q&fo=json", accept = "application/json", aliases = arrayOf("loc", "loc.gov", "libraryofcongress")) ?: return null
    val root = JsonParser.parseString(json).asJsonObject
    val results = root.getAsJsonArray("results") ?: return null
    if (results.size() == 0) return null
    val item = results[0].asJsonObject
    val titleFound = item.get("title")?.asString ?: return null
    val creator = item.get("creator")?.asString
    val publisher = item.get("publisher")?.asString
    val date = item.get("date")?.asString
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = titleFound
    safeYearFrom(date)?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    return makeBibEntry("book", f, sourceTag = "loc.gov")
  }

  private fun parseWorldCatXml(xml: String): Map<String, String>? {
    return try {
      val dbf = DocumentBuilderFactory.newInstance()
      dbf.isNamespaceAware = false
      val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
      val nodes = doc.getElementsByTagName("work")
      if (nodes.length == 0) return null
      val el = nodes.item(0) as Element
      val title = el.getAttribute("title") ?: return null
      val author = el.getAttribute("author")
      val year = el.getAttribute("hyr")
      val map = mutableMapOf<String, String>()
      map["title"] = title
      if (!author.isNullOrBlank()) map["author"] = author
      if (!year.isNullOrBlank()) map["year"] = year
      map
    } catch (_: Throwable) { null }
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

  fun deleteEntry(type: String, key: String): Boolean {
    val path = ensureLibraryExists() ?: return false
    return try {
      val original = Files.readString(path)
      val pattern = Pattern.compile("@${Pattern.quote(type.trim().lowercase())}\\s*\\{\\s*${Pattern.quote(key.trim())}\\s*,[\\s\\S]*?\\}\\n?", Pattern.MULTILINE)
      val m = pattern.matcher(original)
      if (!m.find()) return false
      val updated = m.replaceFirst("")
      Files.writeString(path, updated, StandardCharsets.UTF_8)
      LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())?.refresh(false, false)
      true
    } catch (t: Throwable) {
      log.warn("Failed to delete BibTeX entry", t)
      false
    }
  }

  fun suggestDuplicateKey(baseKey: String): String {
    val existing = readEntries().map { it.key }.toSet()
    if (baseKey !in existing) return baseKey
    var i = 2
    while (true) {
      val cand = "$baseKey-$i"
      if (cand !in existing) return cand
      i++
    }
  }
}
