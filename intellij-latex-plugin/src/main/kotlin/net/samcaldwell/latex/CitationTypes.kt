package net.samcaldwell.latex

import javax.swing.DefaultComboBoxModel

object CitationTypes {
  private val TYPES = listOf(
    // BibLaTeX core and extended (plus common synonyms)
    "article", "book", "mvbook", "inbook", "bookinbook", "suppbook",
    "booklet", "collection", "mvcollection", "incollection", "suppcollection",
    "dataset", "manual", "misc", "online", "electronic", "patent",
    "periodical", "suppperiodical", "proceedings", "mvproceedings", "inproceedings", "conference",
    "reference", "mvreference", "inreference", "report", "set", "software",
    "thesis", "mastersthesis", "phdthesis", "unpublished", "xdata",
    // Custom types
    "customa", "customb", "customc", "customd", "custome", "customf",
    // Additional common domains
    "artwork", "audio", "bibnote", "commentary", "image", "jurisdiction", "legislation", "legal",
    "letter", "movie", "music", "performance", "review", "standard", "video",
    // Legacy/other UI entries kept for compatibility
    "website", "journal", "speech", "conference paper", "dictionary entry", "tv/radio broadcast", "movie/film", "nist"
  )

  fun list(): List<String> = TYPES.distinct().sortedBy { it.lowercase() }
  fun array(): Array<String> = list().toTypedArray()
  fun comboModel(): DefaultComboBoxModel<String> = DefaultComboBoxModel(array())
}
