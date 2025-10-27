package net.samcaldwell.latex

import javax.swing.DefaultComboBoxModel

object CitationTypes {
  private fun baseTypes(): Set<String> = net.samcaldwell.latex.bibtex.BiblatexModel.allowedTypes
  private fun synonyms(): Set<String> = setOf("conference","electronic","mastersthesis","phdthesis","techreport",
    "website","journal","speech","conference paper","dictionary entry","tv/radio broadcast","movie/film","nist")
  private fun customs(): Set<String> = setOf("customa","customb","customc","customd","custome","customf")
  private fun domains(): Set<String> = setOf("artwork","audio","bibnote","commentary","image","jurisdiction","legislation","legal",
    "letter","movie","music","performance","review","standard","video")

  fun list(): List<String> = (baseTypes() + synonyms() + customs() + domains()).distinct().sortedBy { it.lowercase() }
  fun array(): Array<String> = list().toTypedArray()
  fun comboModel(): DefaultComboBoxModel<String> = DefaultComboBoxModel(array())
}
