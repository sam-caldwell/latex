package net.samcaldwell.latex.syntax

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class BibHighlightingLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var start: Int = 0
  private var end: Int = 0
  private var tokenStart: Int = 0
  private var tokenEnd: Int = 0
  private var tokenType: IElementType? = null
  private var lastFieldWasAbstract: Boolean = false
  private var expectAbstractValue: Boolean = false
  private var lastFieldWasTitle: Boolean = false
  private var expectTitleValue: Boolean = false
  private var inTitleValue: Boolean = false
  private var titleDepth: Int = 0
  private var lastFieldWasUrl: Boolean = false
  private var expectUrlValue: Boolean = false
  private var inUrlValue: Boolean = false
  private var urlDepth: Int = 0

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.start = startOffset
    this.end = endOffset
    this.tokenStart = startOffset
    this.tokenEnd = startOffset
    this.tokenType = null
    this.lastFieldWasAbstract = false
    this.expectAbstractValue = false
    this.lastFieldWasTitle = false
    this.expectTitleValue = false
    this.inTitleValue = false
    this.titleDepth = 0
    this.lastFieldWasUrl = false
    this.expectUrlValue = false
    this.inUrlValue = false
    this.urlDepth = 0
    advance()
  }

  override fun getState(): Int = 0
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

    // Special handling when inside title value: emit braces separately and content as TITLE_VALUE
    if (inTitleValue) {
      when (ch) {
        '{' -> {
          titleDepth++
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        }
        '}' -> {
          titleDepth--
          tokenEnd = i + 1
          tokenType = BibTokenTypes.RBRACE
          if (titleDepth <= 0) { inTitleValue = false; lastFieldWasTitle = false }
          return
        }
      }
      // Emit content until next brace or end
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.TITLE_VALUE
      return
    }

    // Special handling when inside url value: emit braces separately and content as URL_VALUE
    if (inUrlValue) {
      when (ch) {
        '{' -> { urlDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { urlDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (urlDepth <= 0) { inUrlValue = false; lastFieldWasUrl = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.URL_VALUE
      return
    }
    // Comment to end-of-line: '%'
    if (ch == '%') {
      i++
      while (i < end && buffer[i] != '\n') i++
      tokenEnd = i
      tokenType = BibTokenTypes.COMMENT
      return
    }
    // Whitespace
    if (ch.isWhitespace()) {
      while (i < end && buffer[i].isWhitespace()) i++
      tokenEnd = i
      tokenType = TokenType.WHITE_SPACE
      return
    }
    // Single-character tokens
    when (ch) {
      '@' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.AT; return }
      '=' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.EQUALS; if (lastFieldWasAbstract) expectAbstractValue = true; if (lastFieldWasTitle) expectTitleValue = true; if (lastFieldWasUrl) expectUrlValue = true; return }
      ',' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.COMMA; lastFieldWasAbstract = false; expectAbstractValue = false; lastFieldWasTitle = false; expectTitleValue = false; lastFieldWasUrl = false; expectUrlValue = false; return }
      '{' -> {
        if (expectAbstractValue) {
          tokenEnd = scanBalancedBraces(i)
          tokenType = BibTokenTypes.ABSTRACT_VALUE
          expectAbstractValue = false
          lastFieldWasAbstract = false
          return
        } else if (expectTitleValue) {
          // Enter title value mode: emit this brace, then TITLE_VALUE/BRACE tokens until closing
          inTitleValue = true
          titleDepth = 1
          expectTitleValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectTitleValue) {
          // Enter title value mode
          inTitleValue = true
          titleDepth = 1
          expectTitleValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectUrlValue) {
          // Enter url value mode
          inUrlValue = true
          urlDepth = 1
          expectUrlValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else {
          tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return
        }
      }
      '}' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; return }
      '(' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.LPAREN; return }
      ')' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.RPAREN; return }
      '#' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.HASH; return }
      '"' -> {
        // Quoted string
        i++
        var escaped = false
        while (i < end) {
          val c = buffer[i]
          if (escaped) { escaped = false; i++ }
          else if (c == '\\') { escaped = true; i++ }
          else if (c == '"') { i++; break }
          else i++
        }
        tokenEnd = i
        if (expectTitleValue) {
          tokenType = BibTokenTypes.TITLE_VALUE
          expectTitleValue = false
          lastFieldWasTitle = false
        } else if (expectUrlValue) {
          tokenType = BibTokenTypes.URL_VALUE
          expectUrlValue = false
          lastFieldWasUrl = false
        } else {
          tokenType = if (expectAbstractValue) BibTokenTypes.ABSTRACT_VALUE else BibTokenTypes.STRING
          if (expectAbstractValue) { expectAbstractValue = false; lastFieldWasAbstract = false }
        }
        return
      }
    }
    // Number
    if (ch.isDigit()) {
      while (i < end && buffer[i].isDigit()) i++
      tokenEnd = i
      tokenType = BibTokenTypes.NUMBER
      return
    }
    // Identifier: letters/digits/_-
    if (ch.isLetter() || ch == '_' || ch == '-') {
      val startId = i
      while (i < end) {
        val c = buffer[i]
        if (c.isLetterOrDigit() || c == '_' || c == '-') i++ else break
      }
      // Best-effort classification: identifier just before '=' is a field name
      var j = i
      while (j < end && buffer[j].isWhitespace()) j++
      val type = if (j < end && buffer[j] == '=') BibTokenTypes.FIELD_NAME else BibTokenTypes.IDENT
      // If directly following '@', treat as entry type
      var k = startId - 1
      while (k >= start && buffer[k].isWhitespace()) k--
      if (k >= start && buffer[k] == '@') {
        tokenEnd = i
        tokenType = BibTokenTypes.ENTRY_TYPE
        return
      }
      tokenEnd = i
      tokenType = type
      if (type == BibTokenTypes.FIELD_NAME) {
        val name = buffer.subSequence(tokenStart, tokenEnd).toString()
        lastFieldWasAbstract = name.equals("abstract", ignoreCase = true)
        lastFieldWasTitle = name.equals("title", ignoreCase = true)
        lastFieldWasUrl = name.equals("url", ignoreCase = true)
      }
      return
    }
    // Fallback: consume one char as IDENT
    tokenEnd = i + 1
    tokenType = BibTokenTypes.IDENT
  }

  private fun scanBalancedBraces(startIdx: Int): Int {
    var i = startIdx
    var depth = 0
    while (i < end) {
      val c = buffer[i]
      if (c == '{') depth++
      if (c == '}') {
        depth--
        if (depth == 0) { i++; break }
      }
      i++
    }
    return i.coerceAtMost(end)
  }
}
