package net.samcaldwell.latex.syntax

import com.intellij.psi.tree.IElementType
import net.samcaldwell.latex.TexLanguage

object TexTokenTypes {
  val COMMAND = IElementType("TEX_COMMAND", TexLanguage)
  val COMMENT = IElementType("TEX_COMMENT", TexLanguage)
  val BRACE_L = IElementType("TEX_BRACE_L", TexLanguage)
  val BRACE_R = IElementType("TEX_BRACE_R", TexLanguage)
  val BRACKET_L = IElementType("TEX_BRACKET_L", TexLanguage)
  val BRACKET_R = IElementType("TEX_BRACKET_R", TexLanguage)
  val MATH_DELIM = IElementType("TEX_MATH_DELIM", TexLanguage)
  val TEXT = IElementType("TEX_TEXT", TexLanguage)
}

