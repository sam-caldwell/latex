package net.samcaldwell.latex

import javax.swing.DefaultComboBoxModel

object CitationTypes {
  private val TYPES = listOf(
    // Core BibTeX/common types
    "article", "book", "inproceedings", "misc", "rfc",
    // Media and web
    "movie/film", "tv/radio broadcast", "website", "journal", "speech",
    // Other scholarly/gray literature and legal
    "thesis (or dissertation)", "patent", "court case", "nist", "personal communication", "dictionary entry",
    "conference paper", "image", "legislation", "video", "song", "report", "regulation"
  )

  fun list(): List<String> = TYPES.distinct().sortedBy { it.lowercase() }
  fun array(): Array<String> = list().toTypedArray()
  fun comboModel(): DefaultComboBoxModel<String> = DefaultComboBoxModel(array())
}
