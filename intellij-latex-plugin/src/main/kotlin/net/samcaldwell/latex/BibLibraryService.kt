package net.samcaldwell.latex

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
    val override = try {
      project.getService(BibliographySettingsService::class.java)?.getLibraryPath()?.trim()
    } catch (_: Throwable) { null }
    if (!override.isNullOrEmpty()) return try { Paths.get(override) } catch (_: Throwable) { null }
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
  private fun yearOf(f: Map<String, String>) = f["year"]?.trim().orEmpty()
  private fun titleOf(f: Map<String, String>) = (f["title"] ?: f["booktitle"]).orEmpty().trim()
  private fun journalOf(f: Map<String, String>) = f["journal"]?.trim().orEmpty()
  private fun publisherOf(f: Map<String, String>) = (f["publisher"] ?: f["institution"]).orEmpty().trim()
  private fun urlOf(f: Map<String, String>) = f["url"]?.trim().orEmpty()
  private fun identifierOf(f: Map<String, String>) = (f["doi"] ?: f["isbn"]).orEmpty().trim()

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
    return authorField.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE))
      .map { it.trim().trim('{','}') }
      .filter { it.isNotEmpty() }
      .map { name ->
        if ("," in name) {
          val idx = name.indexOf(',')
          val family = name.substring(0, idx).trim()
          val given = name.substring(idx + 1).trim().ifEmpty { null }
          family to given
        } else {
          // Corporate or single-token name
          name to null
        }
      }
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
      val entryPattern = Pattern.compile("@([a-zA-Z]+)\\s*\\{\\s*([^,\\s]+)\\s*,([\\s\\S]*?)\\n\\}", Pattern.MULTILINE)
      val m = entryPattern.matcher(text)
      while (m.find()) {
        val type = normalizeType(m.group(1).lowercase())
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
      val normalizedType = normalizeType(entry.type.trim().lowercase())
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
        val fields = entry.fields.toMutableMap()
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
    "music" -> "song"
    else -> t.lowercase().trim()
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
    // Only accept if complete for its type
    return if (isComplete(entry)) {
      if (upsertEntry(entry)) entry else null
    } else null
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
      val bytes = HttpUtil.getBytes(url, accept = "application/pdf", aliases = arrayOf("pdf", java.net.URL(url).host)) ?: return null
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
    for (k in req) {
      val v = entry.fields[k]?.trim().orEmpty()
      if (v.isEmpty()) return false
      if (k == "year" && !v.matches(Regex("\\d{4}"))) return false
    }
    return true
  }

  // Keep in sync with UI required fields for each type.
  private fun requiredKeysForType(t: String): Set<String> = when (t.lowercase()) {
    // Scholarly article requires journal and year
    "article" -> setOf("title", "author", "year", "journal")
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
