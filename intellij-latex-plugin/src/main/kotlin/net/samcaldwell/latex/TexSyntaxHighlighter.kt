package net.samcaldwell.latex

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import net.samcaldwell.latex.syntax.TexHighlightingLexer
import net.samcaldwell.latex.syntax.TexTokenTypes

class TexSyntaxHighlighter : SyntaxHighlighterBase() {
  override fun getHighlightingLexer() = TexHighlightingLexer()

  override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
    TexTokenTypes.COMMAND -> pack(TEX_COMMAND)
    TexTokenTypes.COMMENT -> pack(TEX_COMMENT)
    TexTokenTypes.BRACE_L, TexTokenTypes.BRACE_R -> pack(TEX_BRACES)
    TexTokenTypes.BRACKET_L, TexTokenTypes.BRACKET_R -> pack(TEX_BRACKETS)
    TexTokenTypes.MATH_DELIM -> pack(TEX_MATH)
    TokenType.WHITE_SPACE -> emptyArray()
    else -> pack(HighlighterColors.TEXT)
  }

  companion object Keys {
    val TEX_COMMAND: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
      "TEX_COMMAND", DefaultLanguageHighlighterColors.KEYWORD
    )
    val TEX_COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
      "TEX_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT
    )
    val TEX_BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
      "TEX_BRACES", DefaultLanguageHighlighterColors.BRACES
    )
    val TEX_BRACKETS: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
      "TEX_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS
    )
    val TEX_MATH: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
      "TEX_MATH", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL
    )
  }
}

class TexSyntaxHighlighterFactory : com.intellij.openapi.fileTypes.SyntaxHighlighterFactory() {
  override fun getSyntaxHighlighter(project: com.intellij.openapi.project.Project?, virtualFile: com.intellij.openapi.vfs.VirtualFile?): SyntaxHighlighter = TexSyntaxHighlighter()
}

