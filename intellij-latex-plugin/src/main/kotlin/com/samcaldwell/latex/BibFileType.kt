package com.samcaldwell.latex

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object BibFileType : LanguageFileType(BibLanguage) {
  override fun getName(): String = "BibTeX"
  override fun getDescription(): String = "BibTeX database"
  override fun getDefaultExtension(): String = "bib"
  override fun getIcon(): Icon? = IconLoader.getIcon("/icons/bib.svg", javaClass)
}

