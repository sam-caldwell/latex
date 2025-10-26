package net.samcaldwell.latex.syntax

import com.intellij.psi.tree.IElementType
import net.samcaldwell.latex.BibLanguage

object BibTokenTypes {
  val AT = IElementType("BIB_AT", BibLanguage)
  val ENTRY_TYPE = IElementType("BIB_ENTRY_TYPE", BibLanguage)
  val KEY = IElementType("BIB_KEY", BibLanguage)
  val FIELD_NAME = IElementType("BIB_FIELD_NAME", BibLanguage)
  val EQUALS = IElementType("BIB_EQUALS", BibLanguage)
  val COMMA = IElementType("BIB_COMMA", BibLanguage)
  val HASH = IElementType("BIB_HASH", BibLanguage)
  val LBRACE = IElementType("BIB_LBRACE", BibLanguage)
  val RBRACE = IElementType("BIB_RBRACE", BibLanguage)
  val LPAREN = IElementType("BIB_LPAREN", BibLanguage)
  val RPAREN = IElementType("BIB_RPAREN", BibLanguage)
  val NUMBER = IElementType("BIB_NUMBER", BibLanguage)
  val STRING = IElementType("BIB_STRING", BibLanguage)
  val IDENT = IElementType("BIB_IDENT", BibLanguage)
  val COMMENT = IElementType("BIB_COMMENT", BibLanguage)
  val ABSTRACT_VALUE = IElementType("BIB_ABSTRACT_VALUE", BibLanguage)
  val TITLE_VALUE = IElementType("BIB_TITLE_VALUE", BibLanguage)
  val URL_VALUE = IElementType("BIB_URL_VALUE", BibLanguage)
}
