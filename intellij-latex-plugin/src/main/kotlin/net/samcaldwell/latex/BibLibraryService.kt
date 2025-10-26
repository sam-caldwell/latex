package net.samcaldwell.latex

import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
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

  companion object {
    val ALLOWED_BIBLATEX_TYPES: Set<String> = setOf(
      "article", "book", "booklet", "inbook", "incollection", "inproceedings",
      "manual", "mastersthesis", "misc", "phdthesis", "proceedings",
      "techreport", "unpublished", "online"
    )
    val NONENTRY_DIRECTIVES: Set<String> = setOf("string", "preamble", "comment")
  }

  fun libraryPath(): Path? {
    val override = try {
      project.getService(BibliographySettingsService::class.java)?.getLibraryPath()?.trim()
    } catch (_: Throwable) { null }
    if (!override.isNullOrEmpty()) return try { Paths.get(override) } catch (_: Throwable) { null }

    // In normal usage, default to $HOME/bibliography/library.bib.
    // In headless/unit-test scenarios where Application is unavailable, avoid
    // writing outside the workspace by falling back to project base or null.
    val app = try { ApplicationManager.getApplication() } catch (_: Throwable) { null }
    val isUnitTest = try { app?.isUnitTestMode == true } catch (_: Throwable) { false }
    if (app == null || isUnitTest) {
      val base = project.basePath ?: return null
      return Paths.get(base).resolve("library.bib")
    }

    val home = System.getProperty("user.home") ?: return null
    return Paths.get(home, "bibliography", "library.bib")
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
        Files.createDirectories(path.parent)
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

  // Canonical field names and aliases (BibLaTeX-oriented)
  // - journaltitle is canonical; accept 'journal' as alias for compatibility
  // - location is canonical; accept 'address' as alias
  // - institution is canonical; accept 'school' as alias
  fun canonicalFieldName(name: String): String {
    return when (name.trim().lowercase()) {
      "journal" -> "journaltitle"
      "journaltitle" -> "journaltitle"
      "address" -> "location"
      "location" -> "location"
      "school" -> "institution"
      else -> name.trim().lowercase()
    }
  }

  fun canonicalizeFields(fields: Map<String, String>): Map<String, String> {
    if (fields.isEmpty()) return fields
    val out = linkedMapOf<String, String>()
    for ((kRaw, vRaw) in fields) {
      val k = canonicalFieldName(kRaw)
      // Collapse indentation/newline whitespace for multi-line values
      val v = normalizeMultilineField(vRaw)
      // Prefer existing canonical key if both alias and canonical are present; do not overwrite non-empty with empty
      val cur = out[k]
      if (cur == null || cur.isBlank()) out[k] = v else if (v.isNotBlank() && cur.isBlank()) out[k] = v
    }
    return out
  }

  // Provide display aliases expected by older UI code (e.g., 'journal')
  fun withDisplayAliases(fields: Map<String, String>): Map<String, String> {
    if (fields.isEmpty()) return fields
    val out = linkedMapOf<String, String>()
    out.putAll(fields)
    // Mirror journaltitle -> journal for display only if not already present
    val jt = fields["journaltitle"]?.trim()
    if (!jt.isNullOrEmpty() && !out.containsKey("journal")) out["journal"] = jt
    // Mirror location -> address for display-only, if some views expect it (not used widely yet)
    val loc = fields["location"]?.trim()
    if (!loc.isNullOrEmpty() && !out.containsKey("address")) out["address"] = loc
    // Mirror institution -> school for display-only if needed
    val inst = fields["institution"]?.trim()
    if (!inst.isNullOrEmpty() && !out.containsKey("school")) out["school"] = inst
    return out
  }

  enum class Severity { ERROR, WARNING }
  data class ValidationIssue(val field: String, val message: String, val severity: Severity)

  fun validateEntryDetailed(entry: BibEntry): List<ValidationIssue> {
    val issues = mutableListOf<ValidationIssue>()
    val t = entry.type.trim().lowercase()
    val f = canonicalizeFields(entry.fields)

    fun err(field: String, msg: String) { issues += ValidationIssue(field, msg, Severity.ERROR) }
    fun warn(field: String, msg: String) { issues += ValidationIssue(field, msg, Severity.WARNING) }

    fun has(k: String) = f[canonicalFieldName(k)]?.trim().orEmpty().isNotEmpty()
    fun valOf(k: String) = f[canonicalFieldName(k)]?.trim().orEmpty()

    val required: Set<String> = when (t) {
      // Switch to canonical journaltitle for articles
      "article" -> setOf("author", "title", "journaltitle", "year")
      "book" -> setOf("author", "title", "publisher", "year")
      "inproceedings", "conference paper" -> setOf("author", "title", "year")
      "thesis", "thesis (or dissertation)" -> setOf("author", "title", "year", "publisher")
      "report" -> setOf("author", "title", "year", "publisher")
      "website" -> setOf("title", "publisher", "url")
      "patent" -> setOf("author", "title", "year", "publisher")
      "court case" -> setOf("title", "year")
      else -> setOf("title")
    }

    for (k in required) if (!has(k)) err(k, "Missing required field: $k")

    val y = valOf("year")
    if (y.isNotEmpty() && !y.matches(Regex("(\\d{4}|(?i)n\\.d\\.)"))) err("year", "Year must be 4 digits or n.d.")

    run {
      val doi = valOf("doi"); val isbn = valOf("isbn"); val idVal = if (t == "book") isbn else doi
      if (t == "book") {
        if (idVal.isNotEmpty() && !isValidIsbn(idVal)) err("isbn", "Invalid ISBN format/checksum")
      } else if (t == "patent") {
        if (doi.isNotEmpty() && doi.none { it.isDigit() }) err("doi", "Patent identifier must contain digits")
      } else {
        if (idVal.isNotEmpty() && !isValidDoi(idVal)) err("doi", "Invalid DOI format")
      }
    }

    val url = valOf("url")
    if (url.isNotEmpty()) {
      val ok = isValidHttpUrlRfc3986(url)
      if (!ok) err("url", "Invalid URL (RFC 3986): must be http/https/ftp, absolute, and correctly percent-encoded")
    }

    fun checkBiblatexDate(field: String) {
      val s = valOf(field)
      if (s.isEmpty()) return
      if (!isValidBiblatexDate(s)) err(field, "Date must be YYYY, YYYY-MM, YYYY-MM-DD, a range start/end (open start/end allowed), or n.d.")
    }
    checkBiblatexDate("date")
    checkBiblatexDate("eventdate")
    checkBiblatexDate("origdate")
    checkBiblatexDate("urldate")
    // Editor metadata timestamps use ISO 8601 datetime with optional zone
    fun checkIsoTs(field: String) {
      val s = valOf(field)
      if (s.isEmpty()) return
      if (!isValidIso8601Timestamp(s)) err(field, "Timestamp must be YYYY-MM-DDThh:mm[:ss][Z|±hh:mm]")
    }
    checkIsoTs("created")
    checkIsoTs("modified")
    checkIsoTs("timestamp")

    // If both date and year provided, warn if inconsistent with the first year component
    run {
      val d = valOf("date")
      val y = valOf("year")
      if (d.isNotEmpty() && y.isNotEmpty() && y.matches(Regex("(\\d{4}|(?i)n\\.d\\.)"))) {
        val firstYear = extractFirstYearFromBiblatexDate(d)
        if (firstYear != null && y.matches(Regex("\\d{4}")) && firstYear != y) {
          warn("year", "Year (${y}) does not match date (${d}); consider aligning or using only date")
        }
      }
    }

    val auth = valOf("author")
    if (auth.isNotEmpty()) {
      issues.addAll(validateBiblatexAuthorField(auth))
    }

    fun misspelled(text: String): List<String> = try { SpellcheckUtil.misspelledWords(project, text) } catch (_: Throwable) { emptyList() }
    fun checkSpell(field: String) {
      val v = valOf(field)
      if (v.isEmpty()) return
      val miss = misspelled(v)
      if (miss.isNotEmpty()) warn(field, "Possible spelling issues: ${miss.take(5).joinToString(", ")}${if (miss.size > 5) ", …" else ""}")
    }
    checkSpell("title"); checkSpell("journaltitle"); checkSpell("publisher"); checkSpell("abstract");

    return issues
  }

  // --- BibLaTeX author field validation ----------------------------------
  private fun validateBiblatexAuthorField(raw: String): List<ValidationIssue> {
    val out = mutableListOf<ValidationIssue>()
    // Split authors on top-level 'and'
    val list = splitAuthorsTopLevel(raw)
    if (list.isEmpty()) {
      out += ValidationIssue("author", "Author list is empty", Severity.ERROR)
      return out
    }
    for (p in list) out += validateSingleAuthor(p)
    return out
  }

  private fun splitAuthorsTopLevel(s: String): List<String> {
    val res = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    var i = 0
    fun isWs(ch: Char) = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
    while (i < s.length) {
      val ch = s[i]
      if (ch == '{') { depth++; sb.append(ch); i++; continue }
      if (ch == '}') { depth = (depth - 1).coerceAtLeast(0); sb.append(ch); i++; continue }
      if (depth == 0) {
        // detect case-insensitive 'and' with whitespace boundaries
        if ((ch == 'a' || ch == 'A') && i + 3 <= s.length) {
          val sub = s.substring(i, kotlin.math.min(i + 3, s.length))
          if (sub.equals("and", ignoreCase = true)) {
            val prev = if (sb.isNotEmpty()) sb.last() else null
            val next = if (i + 3 < s.length) s[i + 3] else null
            val prevWs = (prev == null) || isWs(prev)
            val nextWs = (next == null) || isWs(next)
            if (prevWs && nextWs) {
              // flush current token
              val token = sb.toString().trim()
              if (token.isNotEmpty()) res += token
              sb.setLength(0)
              i += 3
              // consume following whitespace
              while (i < s.length && isWs(s[i])) i++
              continue
            }
          }
        }
      }
      sb.append(ch)
      i++
    }
    val tail = sb.toString().trim()
    if (tail.isNotEmpty()) res += tail
    return res
  }

  private fun validateSingleAuthor(partRaw: String): List<ValidationIssue> {
    val out = mutableListOf<ValidationIssue>()
    val part = partRaw.trim()
    if (part.isEmpty()) {
      out += ValidationIssue("author", "Empty author segment", Severity.ERROR)
      return out
    }
    // Organization if properly wrapped in a single outer pair of braces
    if (hasSingleOuterBraces(part)) {
      // check braces are balanced
      if (!bracesBalanced(part)) out += ValidationIssue("author", "Unbalanced braces in organization author: $part", Severity.ERROR)
      return out
    }
    // Personal name; count commas outside inner braces (accents)
    var depth = 0
    var commas = 0
    for (c in part) {
      when (c) {
        '{' -> depth++
        '}' -> depth = (depth - 1).coerceAtLeast(0)
        ',' -> if (depth == 0) commas++
      }
    }
    when (commas) {
      0 -> {
        // Given Family form: require at least two tokens
        val tokens = part.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) out += ValidationIssue("author", "Personal name '$part' should include family and given name(s) or use 'Family, Given' format", Severity.ERROR)
      }
      1 -> {
        val segs = part.split(',')
        val family = segs.getOrNull(0)?.trim().orEmpty()
        val given = segs.getOrNull(1)?.trim().orEmpty()
        if (family.isEmpty() || given.isEmpty()) out += ValidationIssue("author", "Personal name '$part' must be 'Family, Given' with both parts present", Severity.ERROR)
      }
      2 -> {
        val segs = part.split(',')
        val family = segs.getOrNull(0)?.trim().orEmpty()
        val jr = segs.getOrNull(1)?.trim().orEmpty()
        val given = segs.getOrNull(2)?.trim().orEmpty()
        if (family.isEmpty() || given.isEmpty()) out += ValidationIssue("author", "Personal name '$part' must be 'Family, Jr, Given' with family and given present", Severity.ERROR)
      }
      else -> out += ValidationIssue("author", "Personal name '$part' has too many commas; use 'Family, Given' or 'Family, Jr, Given'", Severity.ERROR)
    }
    // Heuristic: warn if likely organization without braces
    val orgHints = listOf("inc", "ltd", "llc", "university", "institute", "association", "committee")
    if (!part.contains(',') && orgHints.any { part.contains(it, ignoreCase = true) }) {
      out += ValidationIssue("author", "Organization author '$part' should be wrapped in braces: {$part}", Severity.WARNING)
    }
    return out
  }

  private fun hasSingleOuterBraces(s: String): Boolean {
    if (!s.startsWith('{') || !s.endsWith('}')) return false
    var depth = 0
    for (i in s.indices) {
      val c = s[i]
      if (c == '{') depth++ else if (c == '}') depth--
      if (depth == 0 && i != s.lastIndex) return false // closed before end: not a single outer pair
    }
    return depth == 0
  }

  private fun bracesBalanced(s: String): Boolean {
    var depth = 0
    for (c in s) {
      if (c == '{') depth++ else if (c == '}') depth--
      if (depth < 0) return false
    }
    return depth == 0
  }

  private fun isValidDoi(s: String): Boolean = s.trim().matches(Regex("(?i)^10\\.\\S+/.+"))
  // BibLaTeX date validation: supports
  // - Single date: YYYY | YYYY-MM | YYYY-MM-DD
  // - Ranges: start/end (both sides single-date), with open start or end allowed ("/YYYY" or "YYYY/")
  // - Special: n.d. (case-insensitive)
  private fun isValidBiblatexDate(s: String): Boolean {
    val v = s.trim()
    if (v.equals("n.d.", ignoreCase = true)) return true
    val parts = v.split('/')
    if (parts.size == 1) return isValidIsoDateLike(parts[0])
    if (parts.size == 2) {
      val a = parts[0].trim()
      val b = parts[1].trim()
      val aOk = a.isEmpty() || isValidIsoDateLike(a)
      val bOk = b.isEmpty() || isValidIsoDateLike(b)
      return aOk && bOk
    }
    return false
  }

  private fun isValidIsoDateLike(s: String): Boolean {
    if (s.isEmpty()) return false
    val m = Regex("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?$").matchEntire(s) ?: return false
    val year = m.groupValues[1].toInt()
    val mm = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    val dd = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (mm != null) {
      if (mm !in 1..12) return false
      if (dd != null) {
        val maxDay = when (mm) {
          1,3,5,7,8,10,12 -> 31
          4,6,9,11 -> 30
          2 -> if (isLeap(year)) 29 else 28
          else -> return false
        }
        if (dd !in 1..maxDay) return false
      }
    }
    return true
  }

  // --- URL validation/normalization (RFC 3986) -----------------------------
  fun isValidHttpUrlRfc3986(s: String): Boolean {
    val v = s.trim()
    if (!(v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true) || v.startsWith("ftp://", ignoreCase = true))) return false
    // No spaces or control chars
    if (v.any { it <= ' ' }) return false
    // Percent-encodings must be %HH
    run {
      var i = 0
      while (i < v.length) {
        if (v[i] == '%') {
          if (i + 2 >= v.length) return false
          if (!v[i + 1].isDigitOrHex() || !v[i + 2].isDigitOrHex()) return false
          i += 3
          continue
        }
        i++
      }
    }
    return try {
      val uri = java.net.URI(v)
      val scheme = uri.scheme?.lowercase()
      val host = uri.host
      scheme in setOf("http", "https", "ftp") && host != null && host.isNotBlank()
    } catch (_: Throwable) { false }
  }

  fun normalizeHttpUrlRfc3986(s: String): String? {
    var v = s.trim()
    if (v.isEmpty()) return null
    // Encode spaces to %20
    if (v.contains(' ')) v = v.replace(" ", "%20")
    // Normalize scheme to lowercase
    runCatching {
      val uri = java.net.URI(v)
      val scheme = uri.scheme?.lowercase()
      if (scheme == null) return@runCatching
      val norm = java.net.URI(
        scheme,
        uri.userInfo,
        uri.host,
        uri.port,
        uri.rawPath,
        uri.rawQuery,
        uri.rawFragment
      )
      v = norm.toASCIIString()
    }
    return if (isValidHttpUrlRfc3986(v)) v else null
  }

  private fun Char.isDigitOrHex(): Boolean = this.isDigit() || (this in 'A'..'F') || (this in 'a'..'f')

  // ISO 8601 timestamp with optional seconds and zone: YYYY-MM-DDThh:mm[:ss][Z|±hh:mm]
  private fun isValidIso8601Timestamp(s: String): Boolean {
    val re = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?(Z|[+-]\\d{2}:\\d{2})?$")
    val m = re.matchEntire(s.trim()) ?: return false
    val y = m.groupValues[1].toInt()
    val mo = m.groupValues[2].toInt()
    val d = m.groupValues[3].toInt()
    val hh = m.groupValues[4].toInt()
    val mi = m.groupValues[5].toInt()
    val ss = m.groupValues.getOrNull(6)?.toIntOrNull()
    if (mo !in 1..12) return false
    val maxDay = when (mo) { 1,3,5,7,8,10,12 -> 31; 4,6,9,11 -> 30; 2 -> if (isLeap(y)) 29 else 28; else -> return false }
    if (d !in 1..maxDay) return false
    if (hh !in 0..23) return false
    if (mi !in 0..59) return false
    if (ss != null && ss !in 0..59) return false
    return true
  }

  private fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)

  private fun extractFirstYearFromBiblatexDate(s: String): String? {
    val v = s.trim()
    if (v.equals("n.d.", ignoreCase = true)) return null
    val first = v.substringBefore('/')
    val m = Regex("^(\\d{4})").find(first.trim()) ?: return null
    return m.groupValues[1]
  }

  // Normalize a BibLaTeX date or date-range to canonical form:
  // - Single date: YYYY | YYYY-MM | YYYY-MM-DD, zero-padded where needed
  // - Range: start/end where each side is a single date (open ends allowed)
  // - n.d. normalized to lowercase 'n.d.'
  // Returns null if the input is not a valid BibLaTeX date
  fun normalizeBiblatexDateField(input: String?): String? {
    val s = input?.trim() ?: return null
    if (s.isEmpty()) return null
    if (s.equals("n.d.", ignoreCase = true)) return "n.d."
    val parts = s.split('/')
    if (parts.size == 1) {
      val norm = normalizeIsoLikeSingle(parts[0].trim())
        ?: normalizeIsoLikeSingleLoose(parts[0].trim())
        ?: return null
      return norm
    }
    if (parts.size == 2) {
      val a = parts[0].trim()
      val b = parts[1].trim()
      val aNorm = if (a.isEmpty()) "" else (normalizeIsoLikeSingle(a) ?: normalizeIsoLikeSingleLoose(a) ?: return null)
      val bNorm = if (b.isEmpty()) "" else (normalizeIsoLikeSingle(b) ?: normalizeIsoLikeSingleLoose(b) ?: return null)
      return "$aNorm/$bNorm"
    }
    return null
  }

  // Normalize timestamp to padded ISO 8601: YYYY-MM-DDThh:mm[:ss][Z|±hh:mm]
  fun normalizeIso8601TimestampField(input: String?): String? {
    val s = input?.trim() ?: return null
    if (s.isEmpty()) return null
    val re = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})T(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?(Z|[+-]\\d{1,2}:\\d{2})?$")
    val m = re.matchEntire(s) ?: return null
    val y = m.groupValues[1]
    val mo = m.groupValues[2].padStart(2, '0')
    val d = m.groupValues[3].padStart(2, '0')
    val hh = m.groupValues[4].padStart(2, '0')
    val mi = m.groupValues[5].padStart(2, '0')
    val ss = m.groupValues.getOrNull(6)?.takeIf { it.isNotEmpty() }?.padStart(2, '0')
    var zone = m.groupValues.getOrNull(7)?.takeIf { it.isNotEmpty() }
    // Pad zone hour if 1 digit (e.g., +2:00 -> +02:00)
    if (zone != null && zone != "Z") {
      val zm = Regex("^([+-])(\\d{1,2}):(\\d{2})$").matchEntire(zone)
      if (zm != null) zone = zm.groupValues[1] + zm.groupValues[2].padStart(2, '0') + ":" + zm.groupValues[3]
    }
    val base = "$y-$mo-$d" + "T" + "$hh:$mi" + (if (ss != null) ":$ss" else "")
    return base + (zone ?: "")
  }

  private fun normalizeIsoLikeSingle(s: String): String? {
    val m = Regex("^(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?$").matchEntire(s) ?: return null
    val y = m.groupValues[1]
    val mm = m.groupValues.getOrNull(2)
    val dd = m.groupValues.getOrNull(3)
    return when {
      mm.isNullOrEmpty() -> y
      dd.isNullOrEmpty() -> y + "-" + mm.padStart(2, '0')
      else -> y + "-" + mm.padStart(2, '0') + "-" + dd.padStart(2, '0')
    }
  }

  private fun normalizeIsoLikeSingleLoose(s: String): String? {
    val m = Regex("(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?").find(s) ?: return null
    val y = m.groupValues[1]
    val mm = m.groupValues.getOrNull(2)
    val dd = m.groupValues.getOrNull(3)
    return when {
      mm.isNullOrEmpty() -> y
      dd.isNullOrEmpty() -> y + "-" + mm.padStart(2, '0')
      else -> y + "-" + mm.padStart(2, '0') + "-" + dd.padStart(2, '0')
    }
  }
  private fun isValidIsbn(raw: String): Boolean {
    val s = raw.uppercase().replace("[^0-9X]".toRegex(), "")
    return when (s.length) {
      10 -> isValidIsbn10(s)
      13 -> isValidIsbn13(s)
      else -> false
    }
  }
  private fun isValidIsbn10(s: String): Boolean {
    if (s.length != 10) return false
    var sum = 0
    for (i in 0 until 9) {
      val c = s[i]
      if (!c.isDigit()) return false
      sum += (10 - i) * (c - '0')
    }
    val last = s[9]
    sum += if (last == 'X') 10 else if (last.isDigit()) (last - '0') else return false
    return sum % 11 == 0
  }
  private fun isValidIsbn13(s: String): Boolean {
    if (s.length != 13 || s.any { !it.isDigit() }) return false
    var sum = 0
    for (i in 0 until 12) {
      val d = s[i] - '0'
      sum += if (i % 2 == 0) d else d * 3
    }
    val check = (10 - (sum % 10)) % 10
    return check == (s[12] - '0')
  }

  // --- Citation formatting (APA-like) -------------------------------------

  fun formatCitationApa(entry: BibEntry): String? {
    return when (entry.type.lowercase()) {
      "patent" -> formatPatentApa(entry)
      "court case" -> formatCourtCaseApa(entry)
      "article" -> formatArticleApa(entry)
      "book" -> formatBookApa(entry)
      "website" -> formatWebsiteApa(entry)
      "inproceedings", "conference paper" -> formatInproceedingsApa(entry)
      else -> null
    }
  }

  fun formatCitationMla(entry: BibEntry): String? {
    return when (entry.type.lowercase()) {
      "patent" -> formatPatentMla(entry)
      "court case" -> formatCourtCaseMla(entry)
      "article" -> formatArticleMla(entry)
      "book" -> formatBookMla(entry)
      "website" -> formatWebsiteMla(entry)
      "inproceedings", "conference paper" -> formatInproceedingsMla(entry)
      else -> null
    }
  }

  fun formatCitationChicago(entry: BibEntry): String? {
    return when (entry.type.lowercase()) {
      "patent" -> formatPatentChicago(entry)
      "court case" -> formatCourtCaseChicago(entry)
      "article" -> formatArticleChicago(entry)
      "book" -> formatBookChicago(entry)
      "website" -> formatWebsiteChicago(entry)
      "inproceedings", "conference paper" -> formatInproceedingsChicago(entry)
      else -> null
    }
  }

  fun formatCitationIeee(entry: BibEntry): String? {
    return when (entry.type.lowercase()) {
      "patent" -> formatPatentIeee(entry)
      "court case" -> formatCourtCaseIeee(entry)
      "article" -> formatArticleIeee(entry)
      "book" -> formatBookIeee(entry)
      "website" -> formatWebsiteIeee(entry)
      "inproceedings", "conference paper" -> formatInproceedingsIeee(entry)
      else -> null
    }
  }

  // --- Common field getters ------------------------------------------------
  private fun yearOf(f: Map<String, String>) = canonicalizeFields(f)["year"]?.trim().orEmpty()
  private fun titleOf(f: Map<String, String>) = canonicalizeFields(f).let { (it["title"] ?: it["booktitle"]).orEmpty().trim() }
  private fun journalOf(f: Map<String, String>) = canonicalizeFields(f).let { (it["journaltitle"] ?: it["journal"]).orEmpty().trim() }
  private fun publisherOf(f: Map<String, String>) = canonicalizeFields(f).let { (it["publisher"] ?: it["institution"]).orEmpty().trim() }
  private fun urlOf(f: Map<String, String>) = canonicalizeFields(f)["url"]?.trim().orEmpty()
  private fun identifierOf(f: Map<String, String>) = canonicalizeFields(f).let { (it["doi"] ?: it["isbn"]).orEmpty().trim() }

  private fun formatPatentApa(entry: BibEntry): String? {
    val f = entry.fields
    val authorsRaw = f["author"].orEmpty().trim()
    val title = (f["title"] ?: f["booktitle"]).orEmpty().trim()
    val date = f["date"]?.trim()
    val year = f["year"]?.trim()
    val id = (f["doi"] ?: f["isbn"]).orEmpty().trim() // identifier stored in doi for patents
    val issuer = (f["publisher"] ?: f["institution"])?.trim().orEmpty()
    val url = f["url"]?.trim().orEmpty()

    if (title.isEmpty() || id.isEmpty() || issuer.isEmpty()) return null

    val authors = parseAuthorsToList(authorsRaw)
    val authorsText = if (authors.isNotEmpty()) formatAuthorsApa(authors) else ""
    val dateText = formatDateForApa(date, year)
    val titleSentence = toSentenceCase(title)

    val sb = StringBuilder()
    if (authorsText.isNotEmpty()) sb.append(authorsText).append(' ')
    if (dateText.isNotEmpty()) sb.append('(').append(dateText).append(')').append('.').append(' ')
    sb.append(titleSentence)
    sb.append(' ').append('(').append(id).append(')').append('.').append(' ')
    sb.append(issuer).append('.')
    if (url.isNotEmpty() && isPublicPatentRecord(url)) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatPatentMla(entry: BibEntry): String? {
    val f = entry.fields
    val authorsRaw = f["author"].orEmpty().trim()
    val title = (f["title"] ?: f["booktitle"]).orEmpty().trim()
    val year = f["year"]?.trim().orEmpty()
    val id = (f["doi"] ?: f["isbn"]).orEmpty().trim()
    val issuer = (f["publisher"] ?: f["institution"])?.trim().orEmpty()
    val url = f["url"]?.trim().orEmpty()
    if (title.isEmpty() || id.isEmpty() || issuer.isEmpty()) return null
    val authors = parseAuthorsToList(authorsRaw)
    val authorsText = if (authors.isNotEmpty()) formatAuthorsMla(authors) else ""
    val sb = StringBuilder()
    if (authorsText.isNotEmpty()) sb.append(authorsText).append('.').append(' ')
    sb.append(title).append(' ').append('(').append(id).append(')').append('.').append(' ')
    if (issuer.isNotEmpty()) sb.append(issuer)
    if (year.isNotEmpty()) { if (issuer.isNotEmpty()) sb.append(',').append(' '); sb.append(year) }
    sb.append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatPatentChicago(entry: BibEntry): String? {
    val f = entry.fields
    val authorsRaw = f["author"].orEmpty().trim()
    val title = (f["title"] ?: f["booktitle"]).orEmpty().trim()
    val year = f["year"]?.trim().orEmpty()
    val id = (f["doi"] ?: f["isbn"]).orEmpty().trim()
    val issuer = (f["publisher"] ?: f["institution"])?.trim().orEmpty()
    val url = f["url"]?.trim().orEmpty()
    if (title.isEmpty() || id.isEmpty() || issuer.isEmpty()) return null
    val authors = parseAuthorsToList(authorsRaw)
    val authorsText = if (authors.isNotEmpty()) formatAuthorsChicago(authors) else ""
    val date = f["date"]?.trim()
    val dateApa = formatDateForApa(date, year)
    val dateC = if (dateApa.isNotEmpty()) toMonthDayYear(dateApa) else year
    val sb = StringBuilder()
    if (authorsText.isNotEmpty()) sb.append(authorsText).append('.').append(' ')
    sb.append(title).append('.').append(' ')
    sb.append(id).append(',').append(' ')
    if (!dateC.isNullOrBlank()) sb.append(dateC).append('.').append(' ')
    sb.append(issuer).append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatPatentIeee(entry: BibEntry): String? {
    val f = entry.fields
    val authorsRaw = f["author"].orEmpty().trim()
    val title = (f["title"] ?: f["booktitle"]).orEmpty().trim()
    val year = f["year"]?.trim().orEmpty()
    val id = (f["doi"] ?: f["isbn"]).orEmpty().trim()
    val issuer = (f["publisher"] ?: f["institution"])?.trim().orEmpty()
    val url = f["url"]?.trim().orEmpty()
    if (title.isEmpty() || id.isEmpty() || issuer.isEmpty()) return null
    val authors = parseAuthorsToList(authorsRaw)
    // IEEE condensed authors: First-initial. Last, ... ; use APA initials formatter
    val authorsText = if (authors.isNotEmpty()) formatAuthorsIeee(authors) else ""
    val sb = StringBuilder()
    if (authorsText.isNotEmpty()) sb.append(authorsText).append(',').append(' ')
    sb.append('"').append(title).append('"').append(',').append(' ')
    sb.append(id).append(',').append(' ')
    // Prefer Month Day, Year if available
    run {
      val dateApa = formatDateForApa(f["date"], year)
      if (dateApa.isNotEmpty()) sb.append(toMonthDayYear(dateApa)).append(',').append(' ') else if (year.isNotEmpty()) sb.append(year).append(',').append(' ')
    }
    sb.append(issuer)
    if (url.isNotEmpty()) sb.append(',').append(' ').append("[Online]. Available: ").append(url)
    sb.append('.')
    return sb.toString()
  }

  private fun parseAuthorsToList(authorField: String): List<Pair<String, String?>> {
    if (authorField.isBlank()) return emptyList()
    return parseAuthorsToNameParts(authorField).map { np ->
      val familyFull = listOfNotNull(np.von, np.family).filter { it.isNotBlank() }.joinToString(" ")
      val famWithJr = if (!np.jr.isNullOrBlank()) "$familyFull, ${np.jr}" else familyFull
      famWithJr to np.given
    }
  }

  data class NameParts(
    val family: String,
    val given: String?,
    val von: String?,
    val jr: String?,
    val corporate: Boolean
  )

  fun parseAuthorsToNameParts(authorField: String): List<NameParts> {
    if (authorField.isBlank()) return emptyList()
    val parts = splitAuthorsTopLevel(authorField)
    val out = mutableListOf<NameParts>()
    for (raw0 in parts) {
      val raw = raw0.trim()
      if (raw.isEmpty()) continue
      if (hasSingleOuterBraces(raw)) {
        out += NameParts(family = raw.trim('{', '}'), given = null, von = null, jr = null, corporate = true)
        continue
      }
      // Count commas outside braces
      var depth = 0
      val commaIdxs = mutableListOf<Int>()
      for (i in raw.indices) {
        val c = raw[i]
        if (c == '{') depth++ else if (c == '}') depth = (depth - 1).coerceAtLeast(0) else if (c == ',' && depth == 0) commaIdxs += i
      }
      if (commaIdxs.isEmpty()) {
        // Given von Family: split tokens brace-aware
        val tokens = braceAwareSplit(raw)
        if (tokens.size == 1) {
          out += NameParts(family = tokens[0], given = null, von = null, jr = null, corporate = false)
        } else {
          var i = tokens.lastIndex
          val familyTokens = mutableListOf<String>()
          familyTokens.add(tokens[i]); i--
          // Prepend lowercase tokens as 'von'
          while (i >= 0 && tokens[i].isNotEmpty() && tokens[i][0].isLowerCase()) { familyTokens.add(0, tokens[i]); i-- }
          val family = familyTokens.last()
          val von = if (familyTokens.size > 1) familyTokens.dropLast(1).joinToString(" ") else null
          val given = if (i >= 0) tokens.subList(0, i + 1).joinToString(" ").trim().ifEmpty { null } else null
          out += NameParts(family = family, given = given, von = von, jr = null, corporate = false)
        }
      } else if (commaIdxs.size == 1) {
        val idx = commaIdxs[0]
        val familyAll = raw.substring(0, idx).trim()
        val given = raw.substring(idx + 1).trim().ifEmpty { null }
        // In Family, Given form: if family starts with lowercase token(s), treat those as von
        val famTokens = braceAwareSplit(familyAll)
        val vonTokens = mutableListOf<String>()
        var fi = 0
        while (fi < famTokens.size && famTokens[fi].isNotEmpty() && famTokens[fi][0].isLowerCase()) { vonTokens += famTokens[fi]; fi++ }
        val family = famTokens.subList(fi, famTokens.size).joinToString(" ")
        val von = if (vonTokens.isNotEmpty()) vonTokens.joinToString(" ") else null
        out += NameParts(family = family, given = given, von = von, jr = null, corporate = false)
      } else {
        val i0 = commaIdxs[0]; val i1 = commaIdxs[1]
        val familyAll = raw.substring(0, i0).trim()
        val jr = raw.substring(i0 + 1, i1).trim().ifEmpty { null }
        val given = raw.substring(i1 + 1).trim().ifEmpty { null }
        val famTokens = braceAwareSplit(familyAll)
        val vonTokens = mutableListOf<String>()
        var fi = 0
        while (fi < famTokens.size && famTokens[fi].isNotEmpty() && famTokens[fi][0].isLowerCase()) { vonTokens += famTokens[fi]; fi++ }
        val family = famTokens.subList(fi, famTokens.size).joinToString(" ")
        val von = if (vonTokens.isNotEmpty()) vonTokens.joinToString(" ") else null
        out += NameParts(family = family, given = given, von = von, jr = jr, corporate = false)
      }
    }
    return out
  }

  private fun braceAwareSplit(s: String): List<String> {
    val res = mutableListOf<String>()
    val sb = StringBuilder()
    var depth = 0
    var i = 0
    fun flush() { val tok = sb.toString().trim(); if (tok.isNotEmpty()) res += tok; sb.setLength(0) }
    while (i < s.length) {
      val c = s[i]
      when (c) {
        '{' -> { depth++; sb.append(c) }
        '}' -> { depth = (depth - 1).coerceAtLeast(0); sb.append(c) }
        ' ', '\t', '\n', '\r' -> { if (depth == 0) flush() else sb.append(c) }
        else -> sb.append(c)
      }
      i++
    }
    flush()
    return res
  }

  private fun formatAuthorsApa(list: List<Pair<String, String?>>): String {
    if (list.isEmpty()) return ""
    val formatted = list.map { (family, given) ->
      if (given == null) family else "$family, ${initialsOf(given)}"
    }
    val n = formatted.size
    return when {
      n == 1 -> formatted[0]
      n == 2 -> formatted[0] + ", & " + formatted[1]
      n in 3..20 -> formatted.dropLast(1).joinToString(", ") + ", & " + formatted.last()
      n > 20 -> formatted.take(20).joinToString(", ") + ", et al."
      else -> formatted.joinToString(", ")
    }
  }

  private fun formatAuthorsMla(list: List<Pair<String, String?>>): String {
    if (list.isEmpty()) return ""
    // MLA: 1 -> "Family, Given"; 2 -> "Family, Given, and Given Family"; >2 -> "Family, Given, et al."
    fun personToMla(family: String, given: String?): String =
      if (given == null) family else "$family, $given"
    return when (list.size) {
      1 -> personToMla(list[0].first, list[0].second)
      2 -> personToMla(list[0].first, list[0].second) + ", and " + (list[1].second?.let { "$it ${list[1].first}" } ?: list[1].first)
      else -> personToMla(list[0].first, list[0].second) + ", et al."
    }
  }

  private fun formatAuthorsChicago(list: List<Pair<String, String?>>): String {
    if (list.isEmpty()) return ""
    // Chicago: "Given Family and Given Family"; corporate names stay as-is
    fun personToChicago(family: String, given: String?): String =
      if (given == null) family else "$given $family"
    return when (list.size) {
      1 -> personToChicago(list[0].first, list[0].second)
      2 -> personToChicago(list[0].first, list[0].second) + " and " + personToChicago(list[1].first, list[1].second)
      else -> list.dropLast(1).joinToString(", ") { (f, g) -> personToChicago(f, g) } + ", and " + personToChicago(list.last().first, list.last().second)
    }
  }

  private fun formatAuthorsIeee(list: List<Pair<String, String?>>): String {
    if (list.isEmpty()) return ""
    // IEEE convention: if >6 authors, use first author + "et al."; else list all with initials
    fun personToIeee(family: String, given: String?): String =
      if (given == null) family else initialsOf(given).replace(" ", " ") + " " + family
    return if (list.size > 6) personToIeee(list[0].first, list[0].second) + ", et al."
    else list.joinToString(", ") { (f, g) -> personToIeee(f, g) }
  }

  private fun initialsOf(given: String): String {
    val tokens = given.split(Regex("[\n\r\t ]+"))
      .flatMap { it.split('-').map { p -> p.trim() } }
      .filter { it.isNotEmpty() }
    return tokens.joinToString(" ") { t ->
      val ch = t.firstOrNull { it.isLetter() } ?: return@joinToString ""
      ch.uppercase() + "."
    }.trim()
  }

  private fun formatDateForApa(date: String?, year: String?): String {
    // Prefer explicit date; fall back to year
    if (!date.isNullOrBlank()) {
      // Try common patterns to render "Year, Month Day"
      // 1) ISO: YYYY-MM-DD or YYYY/MM/DD
      val iso = Regex("^(\\d{4})[-/](\\d{1,2})(?:[-/](\\d{1,2}))?$").matchEntire(date)
      if (iso != null) {
        val y = iso.groupValues[1]
        val m = iso.groupValues[2].toIntOrNull()
        val d = iso.groupValues.getOrNull(3)?.toIntOrNull()
        val monthName = m?.let { monthName(it) }
        return if (monthName != null) {
          if (d != null) "$y, $monthName $d" else "$y, $monthName"
        } else y
      }
      // 2) RFC3339-ish
      try {
        val dt = java.time.OffsetDateTime.parse(date)
        return dt.year.toString() + ", " + monthName(dt.monthValue) + " " + dt.dayOfMonth
      } catch (_: Throwable) { /* ignore */ }
      // 3) Already human-friendly; return as-is
      return date
    }
    return year?.takeIf { it.matches(Regex("\\d{4}")) } ?: ""
  }

  private fun monthName(m: Int): String =
    listOf("January","February","March","April","May","June","July","August","September","October","November","December")[kotlin.math.max(1, kotlin.math.min(12, m)) - 1]

  private fun toSentenceCase(s: String): String {
    if (s.isBlank()) return s
    // Lowercase words except all-caps tokens (likely acronyms)
    val lowered = s.split(Regex("\\s+")).joinToString(" ") { w ->
      val letters = w.count { it.isLetter() }
      val allCaps = letters >= 2 && w.filter { it.isLetter() }.all { it.isUpperCase() }
      if (allCaps) w else w.lowercase()
    }
    val sb = StringBuilder(lowered)
    // Capitalize first alphabetic character
    run {
      var i = 0
      while (i < sb.length) { if (sb[i].isLetter()) { sb.setCharAt(i, sb[i].uppercaseChar()); break }; i++ }
    }
    // Capitalize first letter after a colon + optional spaces
    run {
      var idx = 0
      while (idx < sb.length) {
        val colon = sb.indexOf(':', idx)
        if (colon < 0) break
        var j = colon + 1
        while (j < sb.length && sb[j].isWhitespace()) j++
        if (j < sb.length && sb[j].isLetter()) sb.setCharAt(j, sb[j].uppercaseChar())
        idx = j + 1
      }
    }
    return sb.toString()
  }

  private fun isPublicPatentRecord(url: String): Boolean {
    return try {
      val host = java.net.URI(url).host?.lowercase()?.removePrefix("www.") ?: return false
      // Common official/public registries
      host.endsWith("uspto.gov") ||
      host.contains("epo.org") || host.contains("espacenet") || host.contains("register.epo.org") ||
      host.contains("patentscope.wipo.int") || host.endsWith("wipo.int") ||
      host.contains("patents.google.com") || // widely used public record
      host.endsWith("ipo.gov.uk") || host.endsWith("j-platpat.inpit.go.jp") || host.endsWith("cnipa.gov.cn")
    } catch (_: Throwable) { false }
  }

  private fun formatCourtCaseApa(entry: BibEntry): String? {
    val f = entry.fields
    val caseNameRaw = titleOf(f)
    val caseName = normalizeCaseName(caseNameRaw)
    if (caseName.isBlank()) return null
    val year = yearOf(f)
    val court = publisherOf(f)
    val url = urlOf(f)
    val vol = f["reporter_volume"]?.trim().orEmpty()
    val rep = f["reporter"]?.trim().orEmpty()
    val first = f["first_page"]?.trim().orEmpty()
    val pin = f["pinpoint"]?.trim().orEmpty()
    val docket = f["docket"]?.trim().orEmpty()
    val wl = f["wl"]?.trim().orEmpty()
    val date = f["date"]?.trim()

    val hasReporter = vol.isNotEmpty() && rep.isNotEmpty() && first.isNotEmpty()
    val hasSlip = wl.isNotEmpty() || docket.isNotEmpty()
    val sb = StringBuilder()
    sb.append(caseName).append(',').append(' ')
    if (hasReporter || !hasSlip) {
      if (vol.isEmpty() || rep.isEmpty() || first.isEmpty()) return null
      sb.append(vol).append(' ').append(rep).append(' ').append(first)
      if (pin.isNotEmpty()) sb.append(',').append(' ').append(pin)
      val sup = isSupremeCourt(rep, court)
      // Parenthetical: (Court Year) or (Year) for Supreme Court
      sb.append(' ').append('(')
      if (!sup && court.isNotEmpty()) sb.append(court).append(' ')
      if (year.isEmpty()) return null
      sb.append(year).append(')').append('.')
    } else {
      // Slip/unpublished opinion
      if (docket.isNotEmpty()) sb.append("No. ").append(docket).append(',').append(' ')
      if (wl.isEmpty()) return null else sb.append(wl)
      val paren = buildCaseParenthetical(court, date, year)
      if (paren.isEmpty()) return null else sb.append(' ').append(paren).append('.')
    }
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatCourtCaseMla(entry: BibEntry): String? {
    val f = entry.fields
    val caseName = normalizeCaseName(titleOf(f))
    if (caseName.isBlank()) return null
    val year = yearOf(f)
    val court = publisherOf(f)
    val url = urlOf(f)
    val vol = f["reporter_volume"].orEmpty().trim()
    val rep = f["reporter"].orEmpty().trim()
    val first = f["first_page"].orEmpty().trim()
    val pin = f["pinpoint"].orEmpty().trim()
    val docket = f["docket"].orEmpty().trim()
    val wl = f["wl"].orEmpty().trim()
    val date = f["date"]

    val hasReporter = vol.isNotEmpty() && rep.isNotEmpty() && first.isNotEmpty()
    val hasSlip = docket.isNotEmpty() || wl.isNotEmpty()
    val sb = StringBuilder()
    sb.append(caseName)
    if (hasReporter || !hasSlip) {
      if (vol.isEmpty() || rep.isEmpty() || first.isEmpty()) return null
      sb.append('.').append(' ')
      sb.append(vol).append(' ').append(rep).append(' ').append(first)
      if (pin.isNotEmpty()) sb.append(',').append(' ').append(pin)
      // Parenthetical (Court Year)
      val sup = isSupremeCourt(rep, court)
      val p = StringBuilder("(")
      if (!sup && court.isNotEmpty()) p.append(court).append(' ')
      if (year.isEmpty()) return null else p.append(year)
      p.append(')')
      sb.append(' ').append(p.toString()).append('.')
    } else {
      // Slip format
      sb.append('.').append(' ')
      if (docket.isNotEmpty()) sb.append("No. ").append(docket).append(',').append(' ')
      if (wl.isEmpty()) return null else sb.append(wl)
      val paren = buildCaseParenthetical(court, date, year)
      if (paren.isEmpty()) return null else sb.append(' ').append(paren).append('.')
    }
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatCourtCaseChicago(entry: BibEntry): String? {
    val f = entry.fields
    val caseName = normalizeCaseName(titleOf(f))
    if (caseName.isBlank()) return null
    val year = yearOf(f)
    val court = publisherOf(f)
    val url = urlOf(f)
    val vol = f["reporter_volume"].orEmpty().trim()
    val rep = f["reporter"].orEmpty().trim()
    val first = f["first_page"].orEmpty().trim()
    val pin = f["pinpoint"].orEmpty().trim()
    val docket = f["docket"].orEmpty().trim()
    val wl = f["wl"].orEmpty().trim()
    val date = f["date"]

    val hasReporter = vol.isNotEmpty() && rep.isNotEmpty() && first.isNotEmpty()
    val hasSlip = docket.isNotEmpty() || wl.isNotEmpty()
    val sb = StringBuilder()
    sb.append(caseName).append('.').append(' ')
    if (hasReporter || !hasSlip) {
      if (vol.isEmpty() || rep.isEmpty() || first.isEmpty()) return null
      sb.append(vol).append(' ').append(rep).append(' ').append(first)
      if (pin.isNotEmpty()) sb.append(',').append(' ').append(pin)
      // (Court Year) or (Year) for U.S. Supreme Court
      val sup = isSupremeCourt(rep, court)
      val p = StringBuilder("(")
      if (!sup && court.isNotEmpty()) p.append(court).append(' ')
      if (year.isEmpty()) return null else p.append(year)
      p.append(')')
      sb.append(' ').append(p.toString()).append('.')
    } else {
      // Slip
      if (docket.isNotEmpty()) sb.append("No. ").append(docket).append(',').append(' ')
      if (wl.isEmpty()) return null else sb.append(wl)
      val paren = buildCaseParenthetical(court, date, year)
      if (paren.isEmpty()) return null else sb.append(' ').append(paren).append('.')
    }
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatCourtCaseIeee(entry: BibEntry): String? {
    val f = entry.fields
    val caseName = normalizeCaseName(titleOf(f))
    if (caseName.isBlank()) return null
    val year = yearOf(f)
    val court = publisherOf(f)
    val url = urlOf(f)
    val vol = f["reporter_volume"].orEmpty().trim()
    val rep = f["reporter"].orEmpty().trim()
    val first = f["first_page"].orEmpty().trim()
    val pin = f["pinpoint"].orEmpty().trim()
    val docket = f["docket"].orEmpty().trim()
    val wl = f["wl"].orEmpty().trim()
    val date = f["date"]

    val hasReporter = vol.isNotEmpty() && rep.isNotEmpty() && first.isNotEmpty()
    val hasSlip = docket.isNotEmpty() || wl.isNotEmpty()
    val sb = StringBuilder()
    sb.append(caseName).append(',').append(' ')
    if (hasReporter || !hasSlip) {
      if (vol.isEmpty() || rep.isEmpty() || first.isEmpty()) return null
      sb.append(vol).append(' ').append(rep).append(' ').append(first)
      if (pin.isNotEmpty()) sb.append(',').append(' ').append(pin)
      val sup = isSupremeCourt(rep, court)
      val p = StringBuilder("(")
      if (!sup && court.isNotEmpty()) p.append(court).append(' ')
      if (year.isEmpty()) return null else p.append(year)
      p.append(')')
      sb.append(' ').append(p.toString())
    } else {
      // Slip
      if (docket.isNotEmpty()) sb.append("No. ").append(docket).append(',').append(' ')
      if (wl.isEmpty()) return null else sb.append(wl)
      val paren = buildCaseParenthetical(court, date, year)
      if (paren.isEmpty()) return null else sb.append(' ').append(paren)
    }
    if (url.isNotEmpty()) sb.append('.').append(' ').append("[Online]. Available: ").append(url) else sb.append('.')
    return sb.toString()
  }

  private fun normalizeCaseName(s: String): String {
    if (s.isBlank()) return s
    // Ensure " v. " spacing and period
    return s
      .replace(Regex("\\s+v\\.?\\s+|\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE), " v. ")
      .trim()
  }

  private fun isSupremeCourt(reporter: String, court: String): Boolean {
    val r = reporter.lowercase()
    val c = court.lowercase()
    return r.contains("u.s.") || r.contains("s. ct.") || c.contains("supreme court") || c == "u.s." || c.contains("us supreme")
  }

  private fun buildCaseParenthetical(court: String, date: String?, year: String?): String {
    val c = court.trim()
    val d = formatBluebookDate(date, year)
    if (c.isEmpty() && d.isEmpty()) return ""
    return "(${listOf(c, d).filter { it.isNotEmpty() }.joinToString(" ")})"
  }

  private fun formatBluebookDate(date: String?, year: String?): String {
    // Return "Mar. 1, 2021" style if possible; else year
    if (!date.isNullOrBlank()) {
      // Try ISO forms
      val iso = Regex("^(\\d{4})[-/](\\d{1,2})(?:[-/](\\d{1,2}))?$").matchEntire(date)
      if (iso != null) {
        val y = iso.groupValues[1]
        val m = iso.groupValues[2].toIntOrNull()
        val d = iso.groupValues.getOrNull(3)?.toIntOrNull()
        val mon = m?.let { bluebookMonthAbbrev(it) }
        return if (mon != null && d != null) "$mon $d, $y" else y
      }
      // Fallback: use APA date parse, then convert to Month Day, Year (non-abbrev)
      val apa = formatDateForApa(date, year)
      if (apa.isNotEmpty()) return toMonthDayYear(apa)
    }
    return year?.takeIf { it.matches(Regex("\\d{4}")) } ?: ""
  }

  private fun bluebookMonthAbbrev(m: Int): String = when (m) {
    1 -> "Jan."
    2 -> "Feb."
    3 -> "Mar."
    4 -> "Apr."
    5 -> "May"
    6 -> "June"
    7 -> "July"
    8 -> "Aug."
    9 -> "Sept."
    10 -> "Oct."
    11 -> "Nov."
    12 -> "Dec."
    else -> ""
  }

  private fun toMonthDayYear(apaDate: String): String {
    // Convert "YYYY, Month Day" -> "Month Day, YYYY" if pattern matches; otherwise return input
    val m = Regex("^(\\d{4}),\\s+(.+)").matchEntire(apaDate)
    return if (m != null) m.groupValues[2] + ", " + m.groupValues[1] else apaDate
  }

  // --- APA (other types) ---------------------------------------------------
  private fun formatArticleApa(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val journal = journalOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || authors.isEmpty() || journal.isEmpty() || !year.matches(Regex("\\d{4}"))) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsApa(authors)).append(' ').append('(').append(year).append(')').append('.').append(' ')
    sb.append(toSentenceCase(title)).append('.').append(' ')
    sb.append(journal).append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatBookApa(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val publisher = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || authors.isEmpty() || publisher.isEmpty() || !year.matches(Regex("\\d{4}"))) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsApa(authors)).append(' ').append('(').append(year).append(')').append('.').append(' ')
    sb.append(toSentenceCase(title)).append('.').append(' ')
    sb.append(publisher).append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatWebsiteApa(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val site = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || site.isEmpty() || url.isEmpty()) return null
    val sb = StringBuilder()
    if (authors.isNotEmpty()) sb.append(formatAuthorsApa(authors)).append(' ').append('(').append(year.ifEmpty { "n.d." }).append(')').append('.').append(' ')
    sb.append(toSentenceCase(title)).append('.').append(' ')
    sb.append(site).append('.').append(' ').append(url)
    return sb.toString()
  }

  private fun formatInproceedingsApa(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || authors.isEmpty() || !year.matches(Regex("\\d{4}"))) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsApa(authors)).append(' ').append('(').append(year).append(')').append('.').append(' ')
    sb.append(toSentenceCase(title)).append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  // --- MLA (other types) ---------------------------------------------------
  private fun formatArticleMla(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val journal = journalOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || journal.isEmpty() || authors.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsMla(authors)).append('.').append(' ')
    sb.append('"').append(title).append('"').append('.').append(' ')
    sb.append(journal)
    if (year.isNotEmpty()) sb.append(',').append(' ').append(year)
    sb.append('.'); if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatBookMla(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val publisher = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || publisher.isEmpty() || authors.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsMla(authors)).append('.').append(' ')
    sb.append(title).append('.').append(' ')
    sb.append(publisher)
    if (year.isNotEmpty()) sb.append(',').append(' ').append(year)
    sb.append('.'); if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatWebsiteMla(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val site = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || site.isEmpty() || url.isEmpty()) return null
    val sb = StringBuilder()
    if (authors.isNotEmpty()) sb.append(formatAuthorsMla(authors)).append('.').append(' ')
    sb.append('"').append(title).append('"').append('.').append(' ')
    sb.append(site)
    if (year.isNotEmpty()) sb.append(',').append(' ').append(year)
    sb.append('.').append(' ').append(url)
    return sb.toString()
  }

  private fun formatInproceedingsMla(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || authors.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsMla(authors)).append('.').append(' ')
    sb.append('"').append(title).append('"').append('.'); if (year.isNotEmpty()) sb.append(' ').append(year).append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  // --- Chicago (other types) ----------------------------------------------
  private fun formatArticleChicago(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val journal = journalOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || journal.isEmpty() || authors.isEmpty() || year.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsChicago(authors)).append('.').append(' ')
    sb.append('"').append(title).append('"').append('.').append(' ')
    sb.append(journal).append(' ').append('(').append(year).append(')').append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatBookChicago(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val publisher = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || publisher.isEmpty() || authors.isEmpty() || year.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsChicago(authors)).append('.').append(' ')
    sb.append(title).append('.').append(' ')
    sb.append(publisher).append(',').append(' ').append(year).append('.')
    if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  private fun formatWebsiteChicago(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val site = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || site.isEmpty() || url.isEmpty()) return null
    val sb = StringBuilder()
    if (authors.isNotEmpty()) sb.append(formatAuthorsChicago(authors)).append('.').append(' ')
    sb.append('"').append(title).append('"').append('.').append(' ')
    sb.append(site)
    if (year.isNotEmpty()) sb.append(',').append(' ').append(year)
    sb.append('.').append(' ').append(url)
    return sb.toString()
  }

  private fun formatInproceedingsChicago(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || authors.isEmpty() || year.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsChicago(authors)).append('.').append(' ')
    sb.append('"').append(title).append('"').append('.').append(' ')
    sb.append(year).append('.'); if (url.isNotEmpty()) sb.append(' ').append(url)
    return sb.toString()
  }

  // --- IEEE (other types) --------------------------------------------------
  private fun formatArticleIeee(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val journal = journalOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || journal.isEmpty() || authors.isEmpty() || year.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsIeee(authors)).append(',').append(' ')
    sb.append('"').append(title).append('"').append(',').append(' ')
    sb.append(journal).append(',').append(' ')
    sb.append(year).append('.'); if (url.isNotEmpty()) sb.append(' ').append("[Online]. Available: ").append(url)
    return sb.toString()
  }

  private fun formatBookIeee(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val publisher = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || publisher.isEmpty() || authors.isEmpty() || year.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsIeee(authors)).append(',').append(' ')
    sb.append(title).append('.').append(' ')
    sb.append(publisher).append(',').append(' ').append(year).append('.'); if (url.isNotEmpty()) sb.append(' ').append("[Online]. Available: ").append(url)
    return sb.toString()
  }

  private fun formatWebsiteIeee(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val site = publisherOf(f)
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || site.isEmpty() || url.isEmpty()) return null
    val sb = StringBuilder()
    if (authors.isNotEmpty()) sb.append(formatAuthorsIeee(authors)).append(',').append(' ')
    sb.append('"').append(title).append('"').append(',').append(' ')
    sb.append(site)
    if (year.isNotEmpty()) sb.append(',').append(' ').append(year)
    sb.append('.').append(' ').append("[Online]. Available: ").append(url)
    return sb.toString()
  }

  private fun formatInproceedingsIeee(entry: BibEntry): String? {
    val f = entry.fields
    val title = titleOf(f)
    val authors = parseAuthorsToList(f["author"].orEmpty())
    val year = yearOf(f)
    val url = urlOf(f)
    if (title.isEmpty() || authors.isEmpty() || year.isEmpty()) return null
    val sb = StringBuilder()
    sb.append(formatAuthorsIeee(authors)).append(',').append(' ')
    sb.append('"').append(title).append('"').append(',').append(' ')
    sb.append(year).append('.'); if (url.isNotEmpty()) sb.append(' ').append("[Online]. Available: ").append(url)
    return sb.toString()
  }

  fun readEntries(): List<BibEntry> {
    val path = ensureLibraryExists() ?: return emptyList()
    return try {
      var text = Files.readString(path)
      // Migrate deprecated types: music -> song
      val migrated = text.replace(Regex("(?i)@music\\s*\\{"), "@song{")
      if (migrated != text) {
        Files.writeString(path, migrated, StandardCharsets.UTF_8)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())?.refresh(false, false)
        text = migrated
      }
      val entries = mutableListOf<BibEntry>()
      // Treat each entry as an atomic unit: match from header until a standalone closing brace line
      val entryPattern = Pattern.compile("@([a-zA-Z]+)\\s*\\{\\s*([^,\\s]+)\\s*,([\\s\\S]*?)^\\s*}\\s*", Pattern.MULTILINE)
      val m = entryPattern.matcher(text)
      while (m.find()) {
        val headerType = normalizeType(m.group(1).lowercase())
        val key = m.group(2)
        val body = m.group(3)
        val fields = withDisplayAliases(canonicalizeFields(parseFields(body)))
        val subtype = fields["entrysubtype"]?.trim()?.lowercase()
        val effective = if (headerType == "misc" && !subtype.isNullOrBlank()) normalizeType(subtype) else headerType
        entries.add(BibEntry(effective, key, fields))
      }
      entries
    } catch (t: Throwable) {
      log.warn("Failed to read library.bib entries", t)
      emptyList()
    }
  }

  // Parse all entries from the provided BibTeX text (same semantics as readEntries()).
  fun readEntriesFromString(text: String): List<BibEntry> {
    return try {
      val entries = mutableListOf<BibEntry>()
      val entryPattern = Pattern.compile("@([a-zA-Z]+)\\s*\\{\\s*([^,\\s]+)\\s*,([\\s\\S]*?)^\\s*}\\s*", Pattern.MULTILINE)
      val m = entryPattern.matcher(text)
      while (m.find()) {
        val headerType = normalizeType(m.group(1).lowercase())
        val key = m.group(2)
        val body = m.group(3)
        val fields = withDisplayAliases(canonicalizeFields(parseFields(body)))
        val subtype = fields["entrysubtype"]?.trim()?.lowercase()
        val effective = if (headerType == "misc" && !subtype.isNullOrBlank()) normalizeType(subtype) else headerType
        entries.add(BibEntry(effective, key, fields))
      }
      entries
    } catch (t: Throwable) {
      log.warn("Failed to parse BibTeX text", t)
      emptyList()
    }
  }

  // Serialize a single entry using alphanumeric field order; values escaped.
  fun serializeEntryAlpha(entry: BibEntry): String {
    // Canonicalize before writing to ensure journaltitle, etc.
    val canon = canonicalizeFields(entry.fields)
    val keys = canon.keys.map { it.trim() }.filter { it.isNotEmpty() }.sorted()
    val body = keys.joinToString(separator = ",\n") { k ->
      val vRaw = canon[k]?.trim().orEmpty()
      val v = if (k.equals("year", true) && vRaw.isBlank()) "n.d." else vRaw
      "  ${k} = {${escapeBraces(v)}}"
    }
    return "@${entry.type}{${entry.key},\n${body}\n}\n\n"
  }

  private fun parseFields(body: String): Map<String, String> {
    val map = linkedMapOf<String, String>()
    val fieldPattern = Pattern.compile(
      "([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(\\{([^}]*)\\}|\"([^\"]*)\")\\s*,?",
      Pattern.MULTILINE
    )
    val fm = fieldPattern.matcher(body)
    while (fm.find()) {
      val name = fm.group(1).lowercase()
      val raw = fm.group(3) ?: fm.group(4) ?: ""
      map[name] = normalizeMultilineField(raw)
    }
    return map
  }

  // Collapse indentation/newline whitespace within BibTeX field values to a single space.
  // This treats multi-line wrapped values as a single logical string while preserving content.
  private fun normalizeMultilineField(raw: String): String {
    if (raw.isEmpty()) return raw
    // Normalize line endings then collapse any newline with surrounding horizontal whitespace to one space.
    val unified = raw.replace("\r\n", "\n").replace('\r', '\n')
    // Replace sequences like "\n      ", "  \n\t", or multiple newlines with a single space
    val collapsed = unified.replace(Regex("[ \t]*\n+[ \t]*"), " ")
    // Trim outer whitespace; do not collapse multiple spaces inside a single line beyond newline joins.
    return collapsed.trim()
  }

  fun upsertEntry(entry: BibEntry): Boolean {
    val path = ensureLibraryExists() ?: return false
    return try {
      // Validate before write (non-blocking): log errors but do not reject writes here.
      val issues = validateEntryDetailed(entry)
      if (issues.any { it.severity == Severity.ERROR }) {
        log.warn("Validation issues for ${entry.type}:{${entry.key}}: " + issues.filter { it.severity == Severity.ERROR }.joinToString("; ") { it.field + ": " + it.message })
      }
      val original = Files.readString(path)
      val originalType = normalizeType(entry.type.trim().lowercase())
      val (normalizedType, entrySubType) = mapToAllowedForWrite(originalType)
      val key = entry.key.trim()

      // Regex to find existing entry by type+key, across lines; stop at a standalone closing brace line
      val pattern = Pattern.compile("@${Pattern.quote(normalizedType)}\\s*\\{\\s*${Pattern.quote(key)}\\s*,[\\s\\S]*?^\\s*}\\s*\n?", Pattern.MULTILINE)
      val matcher = pattern.matcher(original)
      val now = try {
        java.time.OffsetDateTime.now().withNano(0).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
      } catch (_: Throwable) {
        // Fallback if offset not available
        java.time.LocalDateTime.now().withNano(0).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      }
      val updated = if (matcher.find()) {
        // Update existing: preserve created if present; always update modified
        val matchText = matcher.group()
        val existingCreated = extractFieldValue(matchText, "created")
        val fields = canonicalizeFields(entry.fields).toMutableMap()
        // If downgraded, preserve UI type in entrysubtype
        if (normalizedType == "misc" && !entrySubType.isNullOrBlank()) fields["entrysubtype"] = entrySubType
        // Ensure year default when absent
        val yr = fields["year"]?.trim()
        if (yr.isNullOrEmpty()) fields["year"] = "n.d."
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
        val fields = canonicalizeFields(entry.fields).toMutableMap()
        if (normalizedType == "misc" && !entrySubType.isNullOrBlank()) fields["entrysubtype"] = entrySubType
        // Ensure year default when absent
        val yr = fields["year"]?.trim()
        if (yr.isNullOrEmpty()) fields["year"] = "n.d."
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

  private fun normalizeType(t: String): String = when (t.lowercase().trim()) {
    // Historical/legacy synonyms seen in older plugin versions
    "music" -> "song"
    // Common BibLaTeX synonyms
    "conference" -> "inproceedings"
    "phd thesis", "phd-thesis" -> "phdthesis"
    "masters thesis", "master's thesis", "masters-thesis" -> "mastersthesis"
    "tech report", "technical report" -> "techreport"
    "website", "web" -> "online"
    else -> t.lowercase().trim()
  }

  // Returns a pair of (fileType, entrySubType?) mapped to the strict BibLaTeX set.
  // If the given type is not one of the allowed types, we serialize as @misc and
  // preserve the UI type in entrysubtype.
  private fun mapToAllowedForWrite(uiType: String): Pair<String, String?> {
    val t = normalizeType(uiType)
    // Directly allowed
    if (ALLOWED_BIBLATEX_TYPES.contains(t)) return t to null
    // Additional synonyms that map to allowed types without needing entrysubtype
    val direct = when (t) {
      "conference" -> "inproceedings"
      "report" -> "techreport"
      "book chapter" -> "incollection"
      else -> null
    }
    if (direct != null && ALLOWED_BIBLATEX_TYPES.contains(direct)) return direct to null
    // Everything else downgraded to misc with entrysubtype preserved
    return "misc" to t
  }

  // Map any input type to one of the strict, allowed BibLaTeX entry types.
  private fun serializeTypeForBib(t: String): String = mapToAllowedForWrite(t).first

  private fun extractFieldValue(entryText: String, fieldName: String): String? {
    return try {
      val p = Pattern.compile("(?i)${Pattern.quote(fieldName)}\\s*=\\s*(\\{([^}]*)\\}|\"([^\"]*)\")")
      val m = p.matcher(entryText)
      if (m.find()) m.group(2) ?: m.group(3) else null
    } catch (_: Throwable) { null }
  }

  // --- crossref/xdata resolution ------------------------------------------
  // Returns a new list of entries where fields are enriched with crossref/xdata parents.
  // Missing fields in a child are filled from parents, without overriding explicit child values.
  fun resolveCrossrefs(entries: List<BibEntry>): List<BibEntry> {
    if (entries.isEmpty()) return entries
    val byKey = entries.associateBy { it.key }
    fun resolveFor(e: BibEntry, seen: MutableSet<String>, depth: Int = 0): Map<String, String> {
      if (depth > 8) return e.fields // guard against cycles
      val base = canonicalizeFields(e.fields).toMutableMap()
      val cref = base["crossref"]?.trim()
      if (!cref.isNullOrBlank() && cref in byKey && cref !in seen) {
        seen += cref
        val parent = byKey[cref]!!
        val pFields = resolveFor(parent, seen, depth + 1)
        for ((k, v) in pFields) if (!base.containsKey(k)) base[k] = v
      }
      val xdata = base["xdata"]?.trim()
      if (!xdata.isNullOrBlank()) {
        // split on commas or 'and'
        val items = xdata.split(Regex("\\s*(?:,|and)\\s+", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        for (id in items) {
          val parent = byKey[id]
          if (parent != null && id !in seen) {
            seen += id
            val pFields = resolveFor(parent, seen, depth + 1)
            for ((k, v) in pFields) if (!base.containsKey(k)) base[k] = v
          }
        }
      }
      return base
    }
    return entries.map { e -> e.copy(fields = resolveFor(e, mutableSetOf(e.key))) }
  }

  private fun buildFieldsBody(fields: Map<String, String>): String {
    // Canonicalize, then order a few common fields first, then the rest alphabetically
    val canon = canonicalizeFields(fields)
    val priority = listOf(
      "author", "title", "year", "journaltitle", "booktitle", "publisher", "doi", "url",
      "source", "verified", "created", "modified"
    )
    val orderedKeys = LinkedHashSet<String>()
    orderedKeys.addAll(priority.filter { canon.containsKey(it) })
    orderedKeys.addAll(canon.keys.sorted())
    return orderedKeys.joinToString(separator = ",\n") { k ->
      val v = canon[k]?.trim().orEmpty()
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
    // Only accept if complete for its type
    return if (isComplete(entry)) {
      if (upsertEntry(entry)) entry else null
    } else null
  }

  // Perform a DOI lookup via doi.org without writing; returns parsed entry or null.
  fun lookupEntryByDoiOrUrl(identifier: String): BibEntry? {
    val doi = extractDoi(identifier.trim()) ?: return null
    val bibtex = fetchBibtexForDoi(doi) ?: return null
    val parsed = parseSingleEntry(bibtex) ?: return null
    return parsed.copy(fields = parsed.fields + mapOf("source" to "automated (doi.org)", "verified" to "false"))
  }

  fun importFromAny(input: String, preferredKey: String? = null): BibEntry? {
    val trimmed = input.trim()
    val settings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(LookupSettingsService::class.java)

    // 1) DOI directly → doi.org BibTeX (only accept if complete)
    val doiDirect = extractDoi(trimmed)
    if (doiDirect != null) {
      importFromDoiOrUrl(doiDirect, preferredKey)?.let { return it }
    }

    // 2) URL → attempt DOI extraction or PDF parse; accept only complete
    if (looksLikeUrl(trimmed)) {
      if (isLikelyPdfUrl(trimmed)) {
        importFromPdfUrl(trimmed, preferredKey)?.takeIf { isComplete(it) }?.let { return it }
      }
      val doi = extractDoiFromUrl(trimmed)
      if (doi != null) {
        importFromDoiOrUrl(doi, preferredKey)?.let { return it }
      }
      // As a fallback, parse as PDF even if extension not obvious
      importFromPdfUrl(trimmed, preferredKey)?.takeIf { isComplete(it) }?.let { return it }
    }

    // 3) ISBN cascade (only accept complete entries)
    val isbn = extractIsbn(trimmed)
    if (isbn != null) {
      val providersByIsbn = settings.providersForQuery(true)
      for (p in providersByIsbn) {
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
        if (entry != null && isComplete(entry)) return withPreferredKey(entry, preferredKey)
      }
      return null
    }

    // 4) Title cascade (only accept complete entries)
    val providersByTitle = settings.providersForQuery(false)
    for (p in providersByTitle) {
      val candidate = when (p) {
        LookupSettingsService.PROVIDER_OPENLIBRARY -> tryOpenLibraryByTitle(trimmed)
        LookupSettingsService.PROVIDER_GOOGLEBOOKS -> tryGoogleBooksByTitle(trimmed)
        LookupSettingsService.PROVIDER_CROSSREF -> crossrefFindDoiByTitle(trimmed)?.let { doi -> importFromDoiOrUrl(doi, preferredKey) }
        LookupSettingsService.PROVIDER_WORLDCAT -> tryWorldCatByTitle(trimmed)
        LookupSettingsService.PROVIDER_BNB -> tryBnbByTitle(trimmed)
        LookupSettingsService.PROVIDER_LOC -> tryLocByTitle(trimmed)
        else -> null
      }
      if (candidate != null && isComplete(candidate)) return withPreferredKey(candidate, preferredKey)
    }

    // 5) Media fallbacks (OMDb/TMDb/MusicBrainz) — accept only complete
    tryOmdbByTitle(trimmed)?.takeIf { isComplete(it) }?.let { return withPreferredKey(it, preferredKey) }
    tryTmdbByTitle(trimmed)?.takeIf { isComplete(it) }?.let { return withPreferredKey(it, preferredKey) }
    tryMusicBrainz(title = trimmed, artist = null)?.takeIf { isComplete(it) }?.let { return withPreferredKey(it, preferredKey) }

    return null
  }

  // Try providers in configured order for an ISBN; do not write; return first hit (even if partial)
  fun lookupByIsbnCascade(isbnRaw: String): BibEntry? {
    val isbn = extractIsbn(isbnRaw.trim()) ?: return null
    val settings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(LookupSettingsService::class.java)
    val providers = settings.providersForQuery(true)
    for (p in providers) {
      val e = when (p) {
        LookupSettingsService.PROVIDER_OPENLIBRARY -> tryOpenLibraryByIsbn(isbn)
        LookupSettingsService.PROVIDER_GOOGLEBOOKS -> tryGoogleBooksByIsbn(isbn)
        LookupSettingsService.PROVIDER_CROSSREF -> tryCrossrefByIsbn(isbn)
        LookupSettingsService.PROVIDER_WORLDCAT -> tryWorldCatByIsbn(isbn)
        LookupSettingsService.PROVIDER_BNB -> tryBnbByIsbn(isbn)
        LookupSettingsService.PROVIDER_OPENBD -> tryOpenBdByIsbn(isbn)
        LookupSettingsService.PROVIDER_LOC -> tryLocByIsbn(isbn)
        else -> null
      }
      if (e != null) return e
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

  // ----- Movie/TV: OMDb ---------------------------------------------------

  private fun tryOmdbByTitle(title: String, yearHint: String? = null): BibEntry? {
    return try {
      val secrets = com.intellij.openapi.application.ApplicationManager.getApplication().getService(LatexSecretsService::class.java)
      val apiKey = secrets.getSecret("apikey.omdb") ?: secrets.getSecret("omdb.apikey") ?: return null
      val q = java.net.URLEncoder.encode(title, "UTF-8")
      val y = yearHint?.takeIf { it.matches(Regex("\\d{4}")) }?.let { "&y=$it" } ?: ""
      val json = HttpUtil.get("https://www.omdbapi.com/?t=$q$y&plot=short&apikey=$apiKey", accept = "application/json", aliases = arrayOf("omdb", "omdbapi.com", "www.omdbapi.com")) ?: return null
      val root = com.google.gson.JsonParser.parseString(json).asJsonObject
      if (!root.get("Response")?.asString.equals("True", true)) return null
      val typ = root.get("Type")?.asString ?: "movie"
      val kind = when (typ.lowercase()) {
        "series" -> "tv/radio broadcast"
        else -> "movie/film"
      }
      val f = linkedMapOf<String, String>()
      f["title"] = root.get("Title")?.asString ?: title
      root.get("Director")?.asString?.takeIf { it.isNotBlank() }?.let { f["author"] = it }
      root.get("Year")?.asString?.let { yv -> safeYearFrom(yv)?.let { f["year"] = it } }
      root.get("Production")?.asString?.takeIf { it.isNotBlank() }?.let { f["publisher"] = it }
      root.get("Plot")?.asString?.takeIf { it.isNotBlank() }?.let { f["abstract"] = it }
      root.get("Genre")?.asString?.takeIf { it.isNotBlank() }?.let { f["keywords"] = it }
      val imdbId = root.get("imdbID")?.asString
      val site = root.get("Website")?.asString
      val url = when {
        !imdbId.isNullOrBlank() -> "https://www.imdb.com/title/$imdbId/"
        !site.isNullOrBlank() -> site
        else -> null
      }
      url?.let { f["url"] = it }
      return makeBibEntry(kind, f, sourceTag = "omdbapi.com")
    } catch (_: Throwable) { null }
  }

  // ----- Movie: TMDb ------------------------------------------------------

  private fun tryTmdbByTitle(title: String): BibEntry? {
    return try {
      val q = java.net.URLEncoder.encode(title, "UTF-8")
      val search = HttpUtil.get("https://api.themoviedb.org/3/search/movie?query=$q&page=1", accept = "application/json", aliases = arrayOf("tmdb", "api.themoviedb.org", "themoviedb.org")) ?: return null
      val root = com.google.gson.JsonParser.parseString(search).asJsonObject
      val results = root.getAsJsonArray("results") ?: return null
      if (results.size() == 0) return null
      val r0 = results[0].asJsonObject
      val id = r0.get("id")?.asInt ?: return null
      val detail = HttpUtil.get("https://api.themoviedb.org/3/movie/$id?append_to_response=credits", accept = "application/json", aliases = arrayOf("tmdb", "api.themoviedb.org", "themoviedb.org")) ?: return null
      val d = com.google.gson.JsonParser.parseString(detail).asJsonObject
      val f = linkedMapOf<String, String>()
      f["title"] = d.get("title")?.asString ?: title
      d.get("release_date")?.asString?.takeIf { it.length >= 4 }?.let { f["year"] = it.substring(0,4) }
      d.get("overview")?.asString?.takeIf { it.isNotBlank() }?.let { f["abstract"] = it }
      // Director(s)
      val credits = d.getAsJsonObject("credits")
      val crew = credits?.getAsJsonArray("crew")
      val directors = mutableListOf<String>()
      crew?.forEach { el ->
        val o = el.asJsonObject
        val job = o.get("job")?.asString
        if (job.equals("Director", true)) o.get("name")?.asString?.let { directors += it }
      }
      if (directors.isNotEmpty()) f["author"] = directors.joinToString(" and ")
      // Studio
      val studios = mutableListOf<String>()
      d.getAsJsonArray("production_companies")?.forEach { el ->
        el.asJsonObject.get("name")?.asString?.let { studios += it }
      }
      if (studios.isNotEmpty()) f["publisher"] = studios.joinToString(", ")
      // URL
      val homepage = d.get("homepage")?.asString
      val url = if (!homepage.isNullOrBlank()) homepage else "https://www.themoviedb.org/movie/$id"
      f["url"] = url
      return makeBibEntry("movie/film", f, sourceTag = "themoviedb.org")
    } catch (_: Throwable) { null }
  }

  // ----- Music: MusicBrainz ----------------------------------------------

  private fun tryMusicBrainz(title: String?, artist: String?): BibEntry? {
    return try {
      val base = StringBuilder("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=1&query=")
      val parts = mutableListOf<String>()
      if (!title.isNullOrBlank()) parts += "recording:" + java.net.URLEncoder.encode(title, "UTF-8")
      if (!artist.isNullOrBlank()) parts += "artist:" + java.net.URLEncoder.encode(artist, "UTF-8")
      if (parts.isEmpty()) return null
      val url = base.append(parts.joinToString("+AND+"))
      val json = HttpUtil.get(url.toString(), accept = "application/json", aliases = arrayOf("musicbrainz.org")) ?: return null
      val root = com.google.gson.JsonParser.parseString(json).asJsonObject
      val recs = root.getAsJsonArray("recordings") ?: return null
      if (recs.size() == 0) return null
      val r0 = recs[0].asJsonObject
      val f = linkedMapOf<String, String>()
      f["title"] = r0.get("title")?.asString ?: (title ?: "")
      // Artists
      val names = mutableListOf<String>()
      r0.getAsJsonArray("artist-credit")?.forEach { el ->
        val o = el.asJsonObject
        val nm = o.get("name")?.asString ?: o.getAsJsonObject("artist")?.get("name")?.asString
        if (!nm.isNullOrBlank()) names += nm
      }
      if (names.isNotEmpty()) f["author"] = names.joinToString(" and ")
      // Year
      val year = r0.get("first-release-date")?.asString ?: r0.get("date")?.asString
      year?.takeIf { it.length >= 4 }?.let { f["year"] = it.substring(0,4) }
      // Label (publisher)
      val releases = r0.getAsJsonArray("releases")
      val labels = mutableListOf<String>()
      releases?.firstOrNull()?.asJsonObject?.getAsJsonArray("label-info")?.forEach { li ->
        val lbl = li.asJsonObject.getAsJsonObject("label")?.get("name")?.asString
        if (!lbl.isNullOrBlank()) labels += lbl
      }
      if (labels.isNotEmpty()) f["publisher"] = labels.joinToString(", ")
      // URL
      val id = r0.get("id")?.asString
      if (!id.isNullOrBlank()) f["url"] = "https://musicbrainz.org/recording/$id"
      // Abstract/disambiguation
      r0.get("disambiguation")?.asString?.takeIf { it.isNotBlank() }?.let { f["abstract"] = it }
      return makeBibEntry("song", f, sourceTag = "musicbrainz.org")
    } catch (_: Throwable) { null }
  }

  // ----- PDF import --------------------------------------------------------

  fun importFromPdfUrl(url: String, preferredKey: String? = null): BibEntry? {
    return try {
      val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
      val bytes = HttpUtil.getBytes(url, accept = "application/pdf", aliases = arrayOf("pdf") + (if (host != null) arrayOf(host) else emptyArray())) ?: return null
      PdfboxCompat.loadFromBytes(bytes).use { doc ->
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
    val type = defaultType
    val author = fields["author"]
    val title = fields["title"]
    val year = fields["year"]
    val base = keyHint ?: canonicalizeKeyBase(author, title, year)
    val key = suggestDuplicateKey(base.ifBlank { "ref" })
    val finalFields = fields.toMutableMap().apply {
      if (!sourceTag.isNullOrBlank()) this["source"] = "automated ($sourceTag)"
      if (!this.containsKey("verified")) this["verified"] = "false"
    }
    return BibEntry(type, key, finalFields)
  }

  // Determine if an entry contains all required fields for its type.
  private fun isComplete(entry: BibEntry): Boolean {
    val type = entry.type.lowercase()
    if (type == "court case") {
      val f = entry.fields
      val title = f["title"].orEmpty().trim()
      val year = f["year"].orEmpty().trim()
      if (title.isEmpty() || !year.matches(Regex("\\d{4}"))) return false
      val hasReporter = (f["reporter_volume"].orEmpty().isNotBlank()
        && f["reporter"].orEmpty().isNotBlank()
        && f["first_page"].orEmpty().isNotBlank())
      val hasSlip = (f["docket"].orEmpty().isNotBlank()
        && f["wl"].orEmpty().isNotBlank()
        && f["date"].orEmpty().isNotBlank()
        && f["publisher"].orEmpty().isNotBlank()) // court required for slip
      return hasReporter || hasSlip
    }
    val req = requiredKeysForType(entry.type)
    val f = canonicalizeFields(entry.fields)
    for (k in req) {
      val v = f[canonicalFieldName(k)]?.trim().orEmpty()
      if (v.isEmpty()) return false
      if (k == "year" && !v.matches(Regex("(\\d{4}|(?i)n\\.d\\.)"))) return false
    }
    return true
  }

  // Keep in sync with UI required fields for each type.
  private fun requiredKeysForType(t: String): Set<String> = when (t.lowercase()) {
    // Scholarly article requires journaltitle and year (canonical)
    "article" -> setOf("title", "author", "year", "journaltitle")
    // Book requires publisher and year
    "book" -> setOf("title", "author", "year", "publisher")
    // RFC requires author, title, year
    "rfc" -> setOf("title", "author", "year")
    // Proceedings: require author, title, year
    "inproceedings", "conference paper" -> setOf("title", "author", "year")
    // Media types
    "movie/film", "video" -> setOf("title", "author", "year", "publisher")
    "song" -> setOf("title", "author", "year", "publisher")
    "tv/radio broadcast" -> setOf("title", "author", "year", "publisher")
    "speech" -> setOf("title", "author", "year")
    // Thesis/report/dictionary entry: require author, title, year, publisher
    "thesis (or dissertation)", "report", "dictionary entry" -> setOf("title", "author", "year", "publisher")
    // NIST standards: title, year, publisher (NIST); authors optional
    "nist" -> setOf("title", "year", "publisher")
    // Patent: inventor(s), year/date, issuing authority, identifier
    "patent" -> setOf("title", "author", "year", "publisher", "doi")
    // Journal document type (non-article) require author, title, year
    "journal" -> setOf("title", "author", "year")
    // Legislation/regulation: title, year, publisher (issuing body)
    "legislation", "regulation" -> setOf("title", "year", "publisher")
    // Court case: minimally require title; rest validated by style formatter
    "court case" -> setOf("title")
    // Misc and others: title only
    else -> setOf("title")
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
    // Atomic single-entry parse: header through standalone closing brace line
    val entryPattern = Pattern.compile("@([a-zA-Z]+)\\s*\\{\\s*([^,\\s]+)\\s*,([\\s\\S]*?)^\\s*}\\s*", Pattern.MULTILINE)
    val m = entryPattern.matcher(bibtex)
    if (!m.find()) return null
    val typeHead = normalizeType(m.group(1).lowercase())
    val key = m.group(2)
    val body = m.group(3)
    val fields = withDisplayAliases(canonicalizeFields(parseFields(body)))
    val subtype = fields["entrysubtype"]?.trim()?.lowercase()
    val effective = if (typeHead == "misc" && !subtype.isNullOrBlank()) normalizeType(subtype) else typeHead
    return BibEntry(effective, key, fields)
  }

  fun deleteEntry(type: String, key: String): Boolean {
    val path = ensureLibraryExists() ?: return false
    return try {
      val original = Files.readString(path)
      val tNorm = normalizeType(type)
      val header = serializeTypeForBib(tNorm)
      var updated: String? = null
      // Try delete with the expected serialized header type
      run {
        val p = Pattern.compile(
          "@${Pattern.quote(header)}\\s*\\{\\s*${Pattern.quote(key.trim())}\\s*,[\\s\\S]*?^\\s*}\\s*\n?",
          Pattern.MULTILINE
        )
        val m = p.matcher(original)
        if (m.find()) updated = m.replaceFirst("")
      }
      // Fallback: delete any entry matching the key regardless of header
      if (updated == null) {
        val pAny = Pattern.compile("@[a-zA-Z]+\\s*\\{\\s*${Pattern.quote(key.trim())}\\s*,[\\s\\S]*?^\\s*}\\s*\n?", Pattern.MULTILINE)
        val m2 = pAny.matcher(original)
        if (m2.find()) updated = m2.replaceFirst("")
      }
      if (updated == null) return false
      val cleaned = updated!!.replace(Regex("\n{3,}"), "\n\n")
      Files.writeString(path, cleaned, StandardCharsets.UTF_8)
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
