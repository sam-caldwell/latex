package net.samcaldwell.latex.syntax

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class TexHighlightingLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var start: Int = 0
  private var end: Int = 0
  private var state: Int = 0
  private var tokenStart: Int = 0
  private var tokenEnd: Int = 0
  private var tokenType: IElementType? = null

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.start = startOffset
    this.end = endOffset
    this.state = initialState
    this.tokenStart = startOffset
    this.tokenEnd = startOffset
    this.tokenType = null
    advance()
  }

  override fun getState(): Int = state
  override fun getTokenType(): IElementType? = tokenType
  override fun getTokenStart(): Int = tokenStart
  override fun getTokenEnd(): Int = tokenEnd
  override fun getBufferSequence(): CharSequence = buffer
  override fun getBufferEnd(): Int = end

  override fun advance() {
    if (tokenEnd >= end) { tokenType = null; return }
    var i = if (tokenEnd > start) tokenEnd else start
    tokenStart = i
    if (i >= end) { tokenType = null; return }

    val ch = buffer[i]
    // Line comment starting with '%'
    if (ch == '%') {
      i++
      while (i < end && buffer[i] != '\n') i++
      tokenEnd = i
      tokenType = TexTokenTypes.COMMENT
      return
    }
    // Whitespace
    if (ch.isWhitespace()) {
      while (i < end && buffer[i].isWhitespace()) i++
      tokenEnd = i
      tokenType = TokenType.WHITE_SPACE
      return
    }
    // Command: \\letters*
    if (ch == '\\') {
      i++
      while (i < end && buffer[i].isLetter()) i++
      tokenEnd = i
      tokenType = TexTokenTypes.COMMAND
      return
    }
    // Punctuation / math
    when (ch) {
      '{' -> { tokenEnd = i + 1; tokenType = TexTokenTypes.BRACE_L; return }
      '}' -> { tokenEnd = i + 1; tokenType = TexTokenTypes.BRACE_R; return }
      '[' -> { tokenEnd = i + 1; tokenType = TexTokenTypes.BRACKET_L; return }
      ']' -> { tokenEnd = i + 1; tokenType = TexTokenTypes.BRACKET_R; return }
      '$' -> {
        // $ or $$
        if (i + 1 < end && buffer[i + 1] == '$') { tokenEnd = i + 2 } else tokenEnd = i + 1
        tokenType = TexTokenTypes.MATH_DELIM
        return
      }
    }
    // Text up to next special char or whitespace
    while (i < end) {
      val c = buffer[i]
      if (c == '%' || c == '\\' || c == '{' || c == '}' || c == '[' || c == ']' || c == '$' || c.isWhitespace()) break
      i++
    }
    tokenEnd = i
    tokenType = TexTokenTypes.TEXT
  }
}

