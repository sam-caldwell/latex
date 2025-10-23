package com.samcaldwell.latex

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object TexFileType : LanguageFileType(TexLanguage) {
  override fun getName(): String = "LaTeX"
  override fun getDescription(): String = "LaTeX document"
  override fun getDefaultExtension(): String = "tex"
  override fun getIcon(): Icon? = IconLoader.getIcon("/icons/tex.svg", javaClass)
}

