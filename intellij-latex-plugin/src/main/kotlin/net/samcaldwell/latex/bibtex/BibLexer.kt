package net.samcaldwell.latex.bibtex

internal enum class TokType { AT, IDENT, NUMBER, LBRACE, RBRACE, LPAREN, RPAREN, EQUALS, COMMA, HASH, QUOTED_STRING, EOF }

internal data class Token(val type: TokType, val text: String, val offset: Int, val line: Int, val col: Int)

internal class BibLexer(private val input: String) {
  private var pos: Int = 0
  private var line: Int = 1
  private var col: Int = 1

  fun currentOffset(): Int = pos
  fun currentLineCol(): Pair<Int, Int> = line to col
  fun source(): String = input

  private fun peek(): Char? = if (pos >= input.length) null else input[pos]
  private fun advance(): Char? {
    val ch = peek() ?: return null
    pos++
    if (ch == '\n') { line++; col = 1 } else { col++ }
    return ch
  }

  private fun skipWsAndComments() {
    while (true) {
      val ch = peek() ?: return
      when {
        ch.isWhitespace() -> advance()
        ch == '%' -> { // comment to end-of-line
          while (true) {
            val c = advance() ?: break
            if (c == '\n') break
          }
        }
        else -> return
      }
    }
  }

  fun nextToken(): Token {
    skipWsAndComments()
    val startOffset = pos
    val (startLine, startCol) = currentLineCol()
    val ch = peek() ?: return Token(TokType.EOF, "", startOffset, startLine, startCol)
    // Single-char tokens
    when (ch) {
      '@' -> { advance(); return Token(TokType.AT, "@", startOffset, startLine, startCol) }
      '{' -> { advance(); return Token(TokType.LBRACE, "{", startOffset, startLine, startCol) }
      '}' -> { advance(); return Token(TokType.RBRACE, "}", startOffset, startLine, startCol) }
      '(' -> { advance(); return Token(TokType.LPAREN, "(", startOffset, startLine, startCol) }
      ')' -> { advance(); return Token(TokType.RPAREN, ")", startOffset, startLine, startCol) }
      '=' -> { advance(); return Token(TokType.EQUALS, "=", startOffset, startLine, startCol) }
      ',' -> { advance(); return Token(TokType.COMMA, ",", startOffset, startLine, startCol) }
      '#' -> { advance(); return Token(TokType.HASH, "#", startOffset, startLine, startCol) }
      '"' -> {
        // quoted string with escapes
        advance() // consume opening quote
        val sb = StringBuilder()
        var escaped = false
        while (true) {
          val c = advance() ?: break
          if (escaped) {
            sb.append(c)
            escaped = false
          } else if (c == '\\') {
            escaped = true
          } else if (c == '"') {
            break
          } else {
            sb.append(c)
          }
        }
        return Token(TokType.QUOTED_STRING, sb.toString(), startOffset, startLine, startCol)
      }
    }
    // Ident or number
    if (ch.isLetter() || ch == '_' || ch == '-') {
      val sb = StringBuilder()
      while (true) {
        val c = peek() ?: break
        if (c.isLetterOrDigit() || c == '_' || c == '-') { sb.append(c); advance() } else break
      }
      return Token(TokType.IDENT, sb.toString(), startOffset, startLine, startCol)
    }
    if (ch.isDigit()) {
      val sb = StringBuilder()
      while (true) {
        val c = peek() ?: break
        if (c.isDigit()) { sb.append(c); advance() } else break
      }
      return Token(TokType.NUMBER, sb.toString(), startOffset, startLine, startCol)
    }
    // Unknown char: skip and continue
    advance()
    return nextToken()
  }

  // Read a braced string starting at the current '{'. Returns content without outer braces and starting offset
  fun readBracedString(): Pair<String, Int>? {
    skipWsAndComments()
    val start = peek()
    if (start != '{') return null
    val startOffset = pos
    val (startLine, startCol) = currentLineCol()
    advance() // consume '{'
    var depth = 0
    val sb = StringBuilder()
    while (true) {
      val c = advance() ?: run {
        // unmatched brace; return what we have
        return Pair(sb.toString(), startOffset)
      }
      if (c == '{') {
        depth++
        sb.append(c)
      } else if (c == '}') {
        if (depth == 0) {
          // matched outer brace; done
          break
        } else {
          depth--
          sb.append(c)
        }
      } else {
        sb.append(c)
      }
    }
    return Pair(sb.toString(), startOffset)
  }
}

