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
    BibTokenTypes.FIELD_NAME -> pack(BIB_FIELD_NAME)
    BibTokenTypes.EQUALS -> pack(BIB_EQUALS)
    BibTokenTypes.COMMA -> pack(BIB_COMMA)
    BibTokenTypes.HASH -> pack(BIB_HASH)
    BibTokenTypes.LBRACE, BibTokenTypes.RBRACE, BibTokenTypes.LPAREN, BibTokenTypes.RPAREN -> pack(BIB_BRACES)
    BibTokenTypes.TITLE_VALUE -> pack(BIB_TITLE_VALUE)
    BibTokenTypes.URL_VALUE -> pack(BIB_URL_VALUE)
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
    val BIB_URL_VALUE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BIB_URL_VALUE", DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE)
  }
}

class BibSyntaxHighlighterFactory : com.intellij.openapi.fileTypes.SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: com.intellij.openapi.project.Project?, virtualFile: com.intellij.openapi.vfs.VirtualFile?): SyntaxHighlighter = BibSyntaxHighlighter()
}
