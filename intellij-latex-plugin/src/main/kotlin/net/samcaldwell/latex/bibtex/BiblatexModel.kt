package net.samcaldwell.latex.bibtex

// Core biblatex data model encoded as code for Verify/Reformat.
// This captures allowed types, required-field constraints (including anyOf), and
// basic field classes for downstream validation/normalization.

object BiblatexModel {
  enum class FieldKind { NAME_LIST, KEY_LIST, LITERAL, INTEGER, RANGE, DATE, URL, DOI, EPRINT, BOOLEAN, VERBATIM, XSV }

  sealed class Requirement {
    data class AllOf(val fields: Set<String>): Requirement()
    data class AnyOf(val fields: Set<String>): Requirement()
  }

  data class EntrySpec(
    val type: String,
    val requirements: List<Requirement>,
    val recommended: Set<String> = emptySet()
  )

  // Canonical types per biblatex (+ frequently used custom domains added elsewhere)
  private val SPECS: Map<String, EntrySpec> = listOf(
    entry("article",      rec = setOf("volume","number","pages","doi","url","urldate"), All("author","title","journaltitle"), Any("year","date")),
    entry("book",         rec = setOf("edition","location","isbn","doi","url"), Any("author","editor"), All("title"), Any("year","date")),
    entry("mvbook",       rec = setOf("edition","location","isbn","doi","url"), All("title"), Any("year","date")),
    entry("inbook",       rec = setOf("pages","publisher","doi","url"), Any("author","editor"), All("title","booktitle"), Any("year","date")),
    entry("bookinbook",   rec = setOf("pages","publisher","doi","url"), Any("author","editor"), All("title","booktitle"), Any("year","date")),
    entry("suppbook",     rec = setOf("publisher","doi","url"), All("title"), Any("year","date")),
    entry("booklet",      rec = setOf("author","date","url"), All("title")),
    entry("collection",   rec = setOf("editor","publisher","doi","url"), All("title"), Any("year","date")),
    entry("mvcollection", rec = setOf("editor","publisher","doi","url"), All("title"), Any("year","date")),
    entry("incollection", rec = setOf("pages","publisher","doi","url"), Any("author","editor"), All("title","booktitle"), Any("year","date")),
    entry("suppcollection", rec = setOf("editor","publisher","doi","url"), All("title"), Any("year","date")),
    entry("dataset",      rec = setOf("version","doi","url","urldate"), All("title"), Any("year","date")),
    entry("manual",       rec = setOf("author","version","url"), All("title")),
    entry("misc",         rec = setOf("author","date","url")),
    entry("online",       rec = setOf("urldate","author","date"), All("title","url")),
    entry("patent",       rec = setOf("holder","type","location","url","urldate","version"), All("author","title","number"), Any("year","date")),
    entry("periodical",   rec = setOf("volume","number","doi","url"), All("title"), Any("year","date")),
    entry("suppperiodical", rec = setOf("volume","number","doi","url"), All("title"), Any("year","date")),
    entry("proceedings",  rec = setOf("editor","publisher","series","volume","number","doi","url"), All("title"), Any("year","date")),
    entry("mvproceedings",rec = setOf("editor","publisher","series","doi","url"), All("title"), Any("year","date")),
    entry("inproceedings",rec = setOf("pages","publisher","series","volume","number","doi","url"), All("author","title","booktitle"), Any("year","date")),
    entry("reference",    rec = setOf("editor","publisher","doi","url"), All("title"), Any("year","date")),
    entry("mvreference",  rec = setOf("editor","publisher","doi","url"), All("title"), Any("year","date")),
    entry("inreference",  rec = setOf("pages","publisher","doi","url"), All("author","title","booktitle"), Any("year","date")),
    entry("report",       rec = setOf("type","number","doi","url"), All("author","title","institution"), Any("year","date")),
    entry("set",          rec = setOf("date"), All("title")),
    entry("software",     rec = setOf("version","url","urldate","publisher","doi"), All("title")),
    entry("thesis",       rec = setOf("type","url","doi"), All("author","title","institution"), Any("year","date")),
    entry("unpublished",  rec = setOf("url","urldate"), All("author","title","note"), Any("year","date")),
    entry("xdata")
  ).associateBy { it.type }

  // Common field kinds used by validation/highlighting
  val FIELD_KINDS: Map<String, FieldKind> = mapOf(
    "author" to FieldKind.NAME_LIST,
    "editor" to FieldKind.NAME_LIST,
    "translator" to FieldKind.NAME_LIST,
    "holder" to FieldKind.NAME_LIST,
    "title" to FieldKind.LITERAL,
    "subtitle" to FieldKind.LITERAL,
    "titleaddon" to FieldKind.LITERAL,
    "booktitle" to FieldKind.LITERAL,
    "journaltitle" to FieldKind.LITERAL,
    "number" to FieldKind.LITERAL,
    "volume" to FieldKind.INTEGER,
    "pages" to FieldKind.RANGE,
    "year" to FieldKind.INTEGER,
    "date" to FieldKind.DATE,
    "url" to FieldKind.URL,
    "doi" to FieldKind.DOI,
    "eprint" to FieldKind.EPRINT,
    "urldate" to FieldKind.DATE,
    "location" to FieldKind.KEY_LIST,
    "keywords" to FieldKind.XSV,
    "note" to FieldKind.LITERAL,
    "version" to FieldKind.LITERAL,
    "type" to FieldKind.LITERAL,
    "institution" to FieldKind.LITERAL,
    "publisher" to FieldKind.LITERAL
  )

  // Allowed types set (public)
  val allowedTypes: Set<String> get() = SPECS.keys

  fun getSpec(type: String): EntrySpec? = SPECS[type.lowercase().trim()]

  // Utility builders
  private fun entry(type: String, rec: Set<String> = emptySet(), vararg reqs: Requirement) = EntrySpec(type, reqs.toList(), rec.map { it.lowercase() }.toSet())
  private fun All(vararg f: String) = Requirement.AllOf(f.map { it.lowercase() }.toSet())
  private fun Any(vararg f: String) = Requirement.AnyOf(f.map { it.lowercase() }.toSet())
}
