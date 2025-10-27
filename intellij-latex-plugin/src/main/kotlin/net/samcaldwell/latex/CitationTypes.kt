package net.samcaldwell.latex

import javax.swing.DefaultComboBoxModel

object CitationTypes {
  // Authoritative list sourced from the code-based biblatex model
  private fun baseTypes(): Set<String> = net.samcaldwell.latex.bibtex.BiblatexModel.allowedTypes
  fun list(): List<String> = baseTypes().distinct().sortedBy { it.lowercase() }
  fun array(): Array<String> = list().toTypedArray()
  fun comboModel(): DefaultComboBoxModel<String> = DefaultComboBoxModel(array())
}
