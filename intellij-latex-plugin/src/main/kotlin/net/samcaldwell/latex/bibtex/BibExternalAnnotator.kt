package net.samcaldwell.latex.bibtex

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import net.samcaldwell.latex.BibLibraryService

/**
 * Background annotator for .bib files.
 * Parses the file and applies syntax/semantic underlines to offending regions.
 */
class BibExternalAnnotator : ExternalAnnotator<BibExternalAnnotator.Context, List<BibExternalAnnotator.Issue>>() {

  data class Context(val project: Project, val text: String, val document: Document?)
  data class Issue(val start: Int, val end: Int, val severity: HighlightSeverity, val message: String)

  override fun collectInformation(file: PsiFile): Context? {
    val project = file.project
    val doc = file.viewProvider.document
    val text = try { doc?.text ?: file.text } catch (_: Throwable) { file.text ?: "" }
    if (text.isEmpty()) return null
    return Context(project, text, doc)
  }

  override fun doAnnotate(collectedInfo: Context?): List<Issue>? {
    if (collectedInfo == null) return null
    val project = collectedInfo.project
    val text = collectedInfo.text

    val issues = mutableListOf<Issue>()
    val parser = BibParser(text)
    val parsed = parser.parse()

    fun addIssue(start: Int, end: Int, msg: String, sev: HighlightSeverity) {
      val s = start.coerceIn(0, text.length)
      val e = end.coerceIn(s + 1, text.length)
      issues += Issue(s, e, sev, msg)
    }

    fun tokenRangeAt(offset: Int): IntRange {
      // Expand to token boundaries for visibility; fall back to a single char
      var s = offset.coerceIn(0, text.length)
      var e = s
      while (s > 0 && !text[s - 1].isWhitespace()) {
        val c = text[s - 1]
        if (c == ',' || c == '{' || c == '}' || c == '(' || c == ')') break
        s--
      }
      while (e < text.length && !text[e].isWhitespace()) {
        val c = text[e]
        if (c == ',' || c == '{' || c == '}' || c == '(' || c == ')') break
        e++
      }
      if (e <= s) e = (s + 1).coerceAtMost(text.length)
      return s until e
    }

    fun valueRangeFrom(offset: Int): IntRange {
      // Heuristic: highlight from value start until the next top-level comma/closer
      var i = offset.coerceIn(0, text.length)
      // Skip leading whitespace
      while (i < text.length && text[i].isWhitespace()) i++
      var brace = 0
      var paren = 0
      var inQuote = false
      var j = i
      while (j < text.length) {
        val c = text[j]
        if (!inQuote) {
          when (c) {
            '"' -> inQuote = true
            '{' -> brace++
            '}' -> if (brace > 0) brace-- else break
            '(' -> paren++
            ')' -> if (paren > 0) paren-- else break
            ',' -> if (brace == 0 && paren == 0) break
          }
        } else {
          if (c == '"' && (j == 0 || text[j - 1] != '\\')) inQuote = false
        }
        j++
      }
      if (j <= i) j = (i + 1).coerceAtMost(text.length)
      return i until j
    }

    // 1) Syntax errors from the parser
    for (pe in parsed.errors) {
      val r = tokenRangeAt(pe.offset)
      addIssue(r.first, r.last, pe.message, HighlightSeverity.ERROR)
    }

    // 2) Build @string map for expansion
    val stringMap = mutableMapOf<String, String>()
    for (node in parsed.file.nodes) if (node is BibNode.StringDirective) {
      stringMap[node.name] = BibParser.flattenValueWith(node.value, stringMap)
    }

    // 3) Collect entries, offsets, and perform semantic validation
    val svc = project.getService(BibLibraryService::class.java)
    val entries = mutableListOf<BibLibraryService.BibEntry>()
    val entryOffsets = mutableMapOf<String, MutableList<Int>>()
    val fieldValueOffsets = mutableMapOf<Pair<String, String>, Int>()

    for (node in parsed.file.nodes) if (node is BibNode.Entry) {
      val fields = linkedMapOf<String, String>()
      for (f in node.fields) {
        val rawName = f.name.lowercase()
        val flat = BibParser.flattenValueWith(f.value, stringMap)
        fields[rawName] = flat
        fieldValueOffsets[node.key to rawName] = f.valueOffset
        // Also map canonical alias to same offset
        runCatching {
          val canon = svc.canonicalFieldName(rawName)
          fieldValueOffsets[node.key to canon] = f.valueOffset
        }
      }
      // Normalize effective type (misc + entrysubtype)
      val headerTypeNorm = runCatching {
        val m = svc.javaClass.getDeclaredMethod("normalizeType", String::class.java)
        m.isAccessible = true
        (m.invoke(svc, node.type.lowercase()) as String)
      }.getOrElse { node.type.lowercase() }
      val subtype = fields["entrysubtype"]?.trim()?.lowercase()
      val effType = if (headerTypeNorm == "misc" && !subtype.isNullOrBlank()) {
        runCatching {
          val m = svc.javaClass.getDeclaredMethod("normalizeType", String::class.java)
          m.isAccessible = true
          (m.invoke(svc, subtype) as String)
        }.getOrElse { subtype!! }
      } else headerTypeNorm
      val canonFields = try { svc.canonicalizeFields(fields) } catch (_: Throwable) { fields }
      entries += BibLibraryService.BibEntry(effType, node.key, canonFields)
      entryOffsets.computeIfAbsent(node.key) { mutableListOf() }.add(node.headerOffset)
    }

    // 3a) Duplicate keys (underline the later duplicates at their headers)
    val dups = entries.groupBy { it.key }.filter { it.value.size > 1 }
    for ((key, _) in dups) {
      val offs = entryOffsets[key] ?: continue
      for (i in 1 until offs.size) {
        val off = offs[i]
        val r = tokenRangeAt(off)
        addIssue(r.first, r.last, "duplicate key: $key", HighlightSeverity.ERROR)
      }
    }

    // 3b) Enforce brace-wrapped top-level values
    for (node in parsed.file.nodes) if (node is BibNode.Entry) {
      for (f in node.fields) {
        val v = f.value
        val parts = v.parts
        val bracedTop = parts.size == 1 && (parts[0] as? Part.Str)?.kind == StrKind.BRACED
        if (!bracedTop) {
          val rr = valueRangeFrom(f.valueOffset)
          addIssue(rr.first, rr.last, "${f.name} – expected brace-wrapped value: {…}", HighlightSeverity.ERROR)
        }
      }
    }

    // 3c) crossref/xdata enrichment before validation
    val enriched = runCatching { svc.resolveCrossrefs(entries) }.getOrElse { entries }

    // 3d) Field-level semantic validation
    for (e in enriched) {
      val problems = try { svc.validateEntryDetailed(e) } catch (_: Throwable) { emptyList() }
      for (p in problems) {
        val name = p.field.lowercase()
        val voff = fieldValueOffsets[e.key to name]
        val range = if (voff != null) valueRangeFrom(voff) else {
          // Fallback to first header offset if value offset unknown
          val eo = entryOffsets[e.key]?.firstOrNull() ?: 0
          tokenRangeAt(eo)
        }
        val sev = if (p.severity == BibLibraryService.Severity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
        addIssue(range.first, range.last, "${p.field} – ${p.message}", sev)
      }
    }

    return issues
  }

  override fun apply(file: PsiFile, annotationResult: List<Issue>?, holder: AnnotationHolder) {
    if (annotationResult == null) return
    for (i in annotationResult) {
      val tr = TextRange(i.start, i.end)
      holder.newAnnotation(i.severity, i.message).range(tr).create()
    }
  }
}

