package net.samcaldwell.latex

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import net.samcaldwell.latex.syntax.BibHighlightingLexer
import net.samcaldwell.latex.syntax.BibTokenTypes

class BibSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer() = BibHighlightingLexer()

  override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
    BibTokenTypes.AT -> pack(BIB_AT)
    BibTokenTypes.ENTRY_TYPE -> pack(BIB_ENTRY_TYPE)
    BibTokenTypes.KEY -> pack(BIB_RECORD_KEY)
    BibTokenTypes.FIELD_NAME -> pack(BIB_FIELD_NAME)
    BibTokenTypes.EQUALS -> pack(BIB_EQUALS)
    BibTokenTypes.COMMA -> pack(BIB_COMMA)
    BibTokenTypes.HASH -> pack(BIB_HASH)
    BibTokenTypes.LBRACE, BibTokenTypes.RBRACE, BibTokenTypes.LPAREN, BibTokenTypes.RPAREN -> pack(BIB_BRACES)
    BibTokenTypes.TITLE_VALUE -> pack(BIB_TITLE_VALUE)
    BibTokenTypes.URL_VALUE -> pack(BIB_URL_VALUE)
    BibTokenTypes.BOOL_VALUE -> pack(BIB_BOOL_VALUE)
    BibTokenTypes.SOURCE_VALUE -> pack(BIB_SOURCE_VALUE)
    BibTokenTypes.KEYWORDS_VALUE -> pack(BIB_KEYWORDS_VALUE)
    BibTokenTypes.DOI_VALUE -> pack(BIB_DOI_VALUE)
    BibTokenTypes.DATE_NUMBER -> pack(BIB_DATE_NUMBER)
    BibTokenTypes.DATE_DELIM -> pack(BIB_DATE_DELIM)
    BibTokenTypes.PAGES_VALUE -> pack(BIB_PAGES_VALUE)
    BibTokenTypes.VOLUME_VALUE -> pack(BIB_VOLUME_VALUE)
    BibTokenTypes.ISBN_VALUE -> pack(BIB_ISBN_VALUE)
    BibTokenTypes.AUTHOR_NAME -> pack(BIB_AUTHOR_NAME)
    BibTokenTypes.AUTHOR_AND -> pack(BIB_AUTHOR_AND)
    BibTokenTypes.NUMBER -> pack(BIB_NUMBER)
    BibTokenTypes.STRING -> pack(BIB_STRING)
    BibTokenTypes.ABSTRACT_VALUE -> pack(BIB_ABSTRACT_VALUE)
    BibTokenTypes.COMMENT -> pack(BIB_COMMENT)
    TokenType.WHITE_SPACE -> emptyArray()
    else -> pack(HighlighterColors.TEXT)
  }

  companion object Keys {
    val BIB_AT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_AT", DefaultLanguageHighlighterColors.MARKUP_TAG)
    val BIB_ENTRY_TYPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_ENTRY_TYPE", DefaultLanguageHighlighterColors.KEYWORD)
    val BIB_RECORD_KEY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_RECORD_KEY", DefaultLanguageHighlighterColors.METADATA)
    val BIB_FIELD_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_FIELD_NAME", DefaultLanguageHighlighterColors.METADATA)
    val BIB_EQUALS: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_EQUALS", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val BIB_COMMA: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val BIB_HASH: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_HASH", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val BIB_BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_BRACES", DefaultLanguageHighlighterColors.BRACES)
    val BIB_NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val BIB_STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_STRING", DefaultLanguageHighlighterColors.STRING)
    val BIB_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    // Map abstract value to a greenish default (doc comment), customize via Color Settings
    val BIB_ABSTRACT_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_ABSTRACT_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val BIB_TITLE_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_TITLE_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    // URL strings default to the same green as other textual values
    val BIB_URL_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_URL_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val BIB_BOOL_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_BOOL_VALUE", DefaultLanguageHighlighterColors.KEYWORD)
    val BIB_SOURCE_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_SOURCE_VALUE", DefaultLanguageHighlighterColors.KEYWORD)
    val BIB_KEYWORDS_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_KEYWORDS_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val BIB_DATE_NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_DATE_NUMBER", DefaultLanguageHighlighterColors.KEYWORD)
    val BIB_DATE_DELIM: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_DATE_DELIM", DefaultLanguageHighlighterColors.BRACES)
    val BIB_DOI_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_DOI_VALUE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val BIB_PAGES_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_PAGES_VALUE", DefaultLanguageHighlighterColors.KEYWORD)
    val BIB_VOLUME_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_VOLUME_VALUE", DefaultLanguageHighlighterColors.KEYWORD)
    val BIB_ISBN_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_ISBN_VALUE", DefaultLanguageHighlighterColors.KEYWORD)
    val BIB_AUTHOR_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_AUTHOR_NAME", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val BIB_AUTHOR_AND: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_AUTHOR_AND", DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE)
  }
}

class BibSyntaxHighlighterFactory : com.intellij.openapi.fileTypes.SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: com.intellij.openapi.project.Project?, virtualFile: com.intellij.openapi.vfs.VirtualFile?): SyntaxHighlighter = BibSyntaxHighlighter()
}
