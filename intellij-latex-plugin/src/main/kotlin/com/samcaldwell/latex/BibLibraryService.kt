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

  fun importFromAny(input: String, preferredKey: String? = null): BibEntry? {
    val trimmed = input.trim()
    // 1) DOI directly → doi.org BibTeX
    extractDoi(trimmed)?.let { doi ->
      return importFromDoiOrUrl(doi, preferredKey)
    }
    // 2) URL → attempt to extract DOI from page content first
    if (looksLikeUrl(trimmed)) {
      val doi = extractDoiFromUrl(trimmed)
      if (doi != null) return importFromDoiOrUrl(doi, preferredKey)
    }

    // 3) ISBN or Title lookup through a cascade of sources
    val isbn = extractIsbn(trimmed)
    if (isbn != null) {
      // For ISBN, prefer: OpenLibrary → Google Books → Crossref → WorldCat Classify → BNB SPARQL → openBD (JP) → US LOC
      tryOpenLibraryByIsbn(isbn)?.let { return withPreferredKey(it, preferredKey) }
      tryGoogleBooksByIsbn(isbn)?.let { return withPreferredKey(it, preferredKey) }
      tryCrossrefByIsbn(isbn)?.let { return withPreferredKey(it, preferredKey) }
      tryWorldCatByIsbn(isbn)?.let { return withPreferredKey(it, preferredKey) }
      tryBnbByIsbn(isbn)?.let { return withPreferredKey(it, preferredKey) }
      tryOpenBdByIsbn(isbn)?.let { return withPreferredKey(it, preferredKey) }
      tryLocByIsbn(isbn)?.let { return withPreferredKey(it, preferredKey) }
      return null
    }

    // Treat input as a title query: OpenLibrary → Google Books → Crossref REST → WorldCat Classify → BNB SPARQL → US LOC
    tryOpenLibraryByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
    tryGoogleBooksByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
    crossrefFindDoiByTitle(trimmed)?.let { doi ->
      importFromDoiOrUrl(doi, preferredKey)?.let { return it }
    }
    tryWorldCatByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
    tryBnbByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }
    tryLocByTitle(trimmed)?.let { return withPreferredKey(it, preferredKey) }

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
    val m = Pattern.compile("\\\"DOI\\\"\\s*:\\s*\\\"(10\\.[^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE).matcher(json)
    return if (m.find()) m.group(1) else null
  }

  private fun fetchBibtexForDoi(doi: String): String? =
    HttpUtil.get("https://doi.org/$doi", accept = "application/x-bibtex; charset=utf-8", aliases = arrayOf("doi", "doi.org"))

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

  private fun makeBibEntry(defaultType: String, fields: Map<String, String>, keyHint: String? = null): BibEntry {
    val type = fields["entrytype"] ?: defaultType
    val author = fields["author"]
    val title = fields["title"]
    val year = fields["year"]
    val base = keyHint ?: canonicalizeKeyBase(author, title, year)
    val key = suggestDuplicateKey(base.ifBlank { "ref" })
    val finalFields = fields.toMutableMap().apply { remove("entrytype") }
    return BibEntry(type, key, finalFields)
  }

  private fun joinAuthors(list: List<String>): String? =
    list.map { it.trim() }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" and ")

  private fun jsonFindString(json: String, field: String): String? {
    val m = Pattern.compile("\\\"${Pattern.quote(field)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json)
    return if (m.find()) m.group(1) else null
  }

  private fun jsonFindArray(json: String, field: String): List<String> {
    val m = Pattern.compile("\\\"${Pattern.quote(field)}\\\"\\s*:\\s*\\[([^]]*)\\]").matcher(json)
    if (!m.find()) return emptyList()
    val body = m.group(1)
    val item = Pattern.compile("\\\"([^\\\"]*)\\\"")
    val r = item.matcher(body)
    val out = mutableListOf<String>()
    while (r.find()) out += r.group(1)
    return out
  }

  private fun tryOpenLibraryByIsbn(isbn: String): BibEntry? {
    val url = "https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"
    val json = HttpUtil.get(url, accept = "application/json", aliases = arrayOf("openlibrary", "openlibrary.org")) ?: return null
    // The root is an object with key "ISBN:isbn"
    val keyMatch = Pattern.compile("\\\"ISBN:$isbn\\\"\\s*:\\s*\\{([\\s\\S]*?)}\\s*(,|})").matcher(json)
    if (!keyMatch.find()) return null
    val obj = keyMatch.group(1)
    val title = jsonFindString(obj, "title")
    val publishers = jsonFindArray(obj, "publishers")
    val authorsNames = Pattern.compile("\\\"authors\\\"\\s*:\\s*\\[([\\s\\S]*?)\\]").matcher(obj).let { m ->
      if (m.find()) {
        val arr = m.group(1)
        val n = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        val mm = n.matcher(arr)
        val out = mutableListOf<String>()
        while (mm.find()) out += mm.group(1)
        out
      } else emptyList()
    }
    val date = jsonFindString(obj, "publish_date")
    val year = safeYearFrom(date)
    if (title.isNullOrBlank()) return null
    val f = linkedMapOf<String, String>()
    joinAuthors(authorsNames)?.let { f["author"] = it }
    f["title"] = title
    year?.let { f["year"] = it }
    if (publishers.isNotEmpty()) f["publisher"] = publishers.first()
    f["isbn"] = isbn
    jsonFindString(obj, "url")?.let { f["url"] = it }
    return makeBibEntry("book", f)
  }

  private fun tryOpenLibraryByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val json = HttpUtil.get("https://openlibrary.org/search.json?limit=1&title=$q", accept = "application/json", aliases = arrayOf("openlibrary", "openlibrary.org")) ?: return null
    val doc = Pattern.compile("\\\"docs\\\"\\s*:\\s*\\[([\\s\\S]*?)\\]").matcher(json).let { m -> if (m.find()) m.group(1) else null } ?: return null
    val titleFound = jsonFindString(doc, "title") ?: jsonFindString(doc, "title_suggest")
    if (titleFound.isNullOrBlank()) return null
    val authors = jsonFindArray(doc, "author_name")
    val year = jsonFindString(doc, "first_publish_year") ?: jsonFindString(doc, "publish_year")
    val isbn = jsonFindArray(doc, "isbn").firstOrNull()
    // If ISBN surfaced, prefer proceeding to DOI via other services later; we can still build a book entry now.
    val f = linkedMapOf<String, String>()
    joinAuthors(authors)?.let { f["author"] = it }
    f["title"] = titleFound
    safeYearFrom(year)?.let { f["year"] = it }
    if (!isbn.isNullOrBlank()) f["isbn"] = isbn
    return makeBibEntry("book", f)
  }

  private fun tryGoogleBooksByIsbn(isbn: String): BibEntry? {
    val json = HttpUtil.get("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn&maxResults=1", accept = "application/json", aliases = arrayOf("googlebooks", "books.googleapis.com", "www.googleapis.com")) ?: return null
    val vi = Pattern.compile("\\\"volumeInfo\\\"\\s*:\\s*\\{([\\s\\S]*?)\\}").matcher(json).let { m -> if (m.find()) m.group(1) else null } ?: return null
    val title = jsonFindString(vi, "title")
    if (title.isNullOrBlank()) return null
    val authors = jsonFindArray(vi, "authors")
    val publisher = jsonFindString(vi, "publisher")
    val date = jsonFindString(vi, "publishedDate")
    val year = safeYearFrom(date)
    val f = linkedMapOf<String, String>()
    joinAuthors(authors)?.let { f["author"] = it }
    f["title"] = title
    year?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    f["isbn"] = isbn
    return makeBibEntry("book", f)
  }

  private fun tryGoogleBooksByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val json = HttpUtil.get("https://www.googleapis.com/books/v1/volumes?q=intitle:$q&maxResults=1", accept = "application/json", aliases = arrayOf("googlebooks", "books.googleapis.com", "www.googleapis.com")) ?: return null
    val vi = Pattern.compile("\\\"volumeInfo\\\"\\s*:\\s*\\{([\\s\\S]*?)\\}").matcher(json).let { m -> if (m.find()) m.group(1) else null } ?: return null
    val titleFound = jsonFindString(vi, "title") ?: return null
    val authors = jsonFindArray(vi, "authors")
    val publisher = jsonFindString(vi, "publisher")
    val date = jsonFindString(vi, "publishedDate")
    val year = safeYearFrom(date)
    val f = linkedMapOf<String, String>()
    joinAuthors(authors)?.let { f["author"] = it }
    f["title"] = titleFound
    year?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    return makeBibEntry("book", f)
  }

  private fun tryCrossrefByIsbn(isbn: String): BibEntry? {
    val q = java.net.URLEncoder.encode(isbn, "UTF-8")
    val json = HttpUtil.get("https://api.crossref.org/works?rows=1&query.bibliographic=$q", accept = "application/json", aliases = arrayOf("crossref", "api.crossref.org")) ?: return null
    val doi = Pattern.compile("\\\"DOI\\\"\\s*:\\s*\\\"(10\\.[^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    if (!doi.isNullOrBlank()) {
      val bib = fetchBibtexForDoi(doi)
      if (!bib.isNullOrBlank()) return parseSingleEntry(bib)
    }
    // Fall back to building a minimal entry if title present
    val titleArr = Pattern.compile("\\\"title\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    if (!titleArr.isNullOrBlank()) {
      val year = Pattern.compile("\\\"issued\\\"[^{]*\\{[^}]*\\[\\s*(\\d{4})").matcher(json).let { m -> if (m.find()) m.group(1) else null }
      val f = linkedMapOf<String, String>()
      f["title"] = titleArr
      year?.let { f["year"] = it }
      f["isbn"] = isbn
      if (!doi.isNullOrBlank()) f["doi"] = doi
      return makeBibEntry("book", f)
    }
    return null
  }

  private fun tryWorldCatByIsbn(isbn: String): BibEntry? {
    val url = "http://classify.oclc.org/classify2/Classify?isbn=$isbn&summary=true"
    val xml = HttpUtil.get(url, accept = "application/xml", aliases = arrayOf("oclc", "worldcat", "classify.oclc.org")) ?: return null
    val m = Pattern.compile("<work[^>]*title=\"([^\"]+)\"[^>]*author=\"([^\"]*)\"[^>]*hyr=\"(\\d{4})?\"").matcher(xml)
    if (!m.find()) return null
    val title = m.group(1)
    val author = m.group(2)
    val year = m.group(3)
    val f = linkedMapOf<String, String>()
    if (!author.isNullOrBlank()) f["author"] = author
    f["title"] = title
    if (!year.isNullOrBlank()) f["year"] = year
    f["isbn"] = isbn
    return makeBibEntry("book", f)
  }

  private fun tryWorldCatByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val xml = HttpUtil.get("http://classify.oclc.org/classify2/Classify?title=$q&summary=true", accept = "application/xml", aliases = arrayOf("oclc", "worldcat", "classify.oclc.org")) ?: return null
    val m = Pattern.compile("<work[^>]*title=\"([^\"]+)\"[^>]*author=\"([^\"]*)\"[^>]*hyr=\"(\\d{4})?\"").matcher(xml)
    if (!m.find()) return null
    val f = linkedMapOf<String, String>()
    val author = m.group(2)
    if (!author.isNullOrBlank()) f["author"] = author
    f["title"] = m.group(1)
    m.group(3)?.let { f["year"] = it }
    return makeBibEntry("book", f)
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
    val title = Pattern.compile("\\\"title\\\"\\s*:\\s*\\{[^{]*\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    if (title.isNullOrBlank()) return null
    val creator = Pattern.compile("\\\"creator\\\"\\s*:\\s*\\{[^{]*\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    val date = Pattern.compile("\\\"date\\\"\\s*:\\s*\\{[^{]*\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = title
    safeYearFrom(date)?.let { f["year"] = it }
    f["isbn"] = isbn
    return makeBibEntry("book", f)
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
    val titleFound = Pattern.compile("\\\"title\\\"\\s*:\\s*\\{[^{]*\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    if (titleFound.isNullOrBlank()) return null
    val creator = Pattern.compile("\\\"creator\\\"\\s*:\\s*\\{[^{]*\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    val date = Pattern.compile("\\\"date\\\"\\s*:\\s*\\{[^{]*\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json).let { m -> if (m.find()) m.group(1) else null }
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = titleFound
    safeYearFrom(date)?.let { f["year"] = it }
    return makeBibEntry("book", f)
  }

  private fun tryOpenBdByIsbn(isbn: String): BibEntry? {
    val json = HttpUtil.get("https://api.openbd.jp/v1/get?isbn=$isbn", accept = "application/json", aliases = arrayOf("openbd", "openbd.jp")) ?: return null
    // Response is an array; if first element is null, not found
    if (json.contains("[null]")) return null
    val summary = Pattern.compile("\\\"summary\\\"\\s*:\\s*\\{([\\s\\S]*?)\\}").matcher(json).let { m -> if (m.find()) m.group(1) else null } ?: return null
    val title = jsonFindString(summary, "title") ?: return null
    val author = jsonFindString(summary, "author")
    val publisher = jsonFindString(summary, "publisher")
    val pubdate = jsonFindString(summary, "pubdate")
    val year = safeYearFrom(pubdate)
    val f = linkedMapOf<String, String>()
    if (!author.isNullOrBlank()) f["author"] = author
    f["title"] = title
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    if (!year.isNullOrBlank()) f["year"] = year
    f["isbn"] = isbn
    return makeBibEntry("book", f)
  }

  private fun tryLocByIsbn(isbn: String): BibEntry? {
    val json = HttpUtil.get("https://www.loc.gov/books/?q=isbn:$isbn&fo=json", accept = "application/json", aliases = arrayOf("loc", "loc.gov", "libraryofcongress")) ?: return null
    val item = Pattern.compile("\\\"results\\\"\\s*:\\s*\\[([\\s\\S]*?)\\]").matcher(json).let { m -> if (m.find()) m.group(1) else null } ?: return null
    val title = jsonFindString(item, "title") ?: return null
    val creator = jsonFindString(item, "creator")
    val publisher = jsonFindString(item, "publisher")
    val date = jsonFindString(item, "date")
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = title
    safeYearFrom(date)?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    f["isbn"] = isbn
    return makeBibEntry("book", f)
  }

  private fun tryLocByTitle(title: String): BibEntry? {
    val q = java.net.URLEncoder.encode(title, "UTF-8")
    val json = HttpUtil.get("https://www.loc.gov/books/?q=$q&fo=json", accept = "application/json", aliases = arrayOf("loc", "loc.gov", "libraryofcongress")) ?: return null
    val item = Pattern.compile("\\\"results\\\"\\s*:\\s*\\[([\\s\\S]*?)\\]").matcher(json).let { m -> if (m.find()) m.group(1) else null } ?: return null
    val titleFound = jsonFindString(item, "title") ?: return null
    val creator = jsonFindString(item, "creator")
    val publisher = jsonFindString(item, "publisher")
    val date = jsonFindString(item, "date")
    val f = linkedMapOf<String, String>()
    if (!creator.isNullOrBlank()) f["author"] = creator
    f["title"] = titleFound
    safeYearFrom(date)?.let { f["year"] = it }
    if (!publisher.isNullOrBlank()) f["publisher"] = publisher
    return makeBibEntry("book", f)
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
