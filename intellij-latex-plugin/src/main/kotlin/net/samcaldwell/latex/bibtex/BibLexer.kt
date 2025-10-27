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

  // Read a parenthesized string starting at the current '('. Returns content without outer parens and starting offset
  fun readParenString(): Pair<String, Int>? {
    skipWsAndComments()
    val start = peek()
    if (start != '(') return null
    val startOffset = pos
    advance() // consume '('
    var depth = 0
    val sb = StringBuilder()
    while (true) {
      val c = advance() ?: run {
        return Pair(sb.toString(), startOffset)
      }
      if (c == '(') {
        depth++
        sb.append(c)
      } else if (c == ')') {
        if (depth == 0) {
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

  // Read a quoted string starting at the current '"'. Returns content without quotes and starting offset
  fun readQuotedString(): Pair<String, Int>? {
    skipWsAndComments()
    val start = peek()
    if (start != '"') return null
    val startOffset = pos
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
    return Pair(sb.toString(), startOffset)
  }

  // Read a generic entry key (after '@type{' or '@type('), stopping before the comma or closing delimiter.
  // Advances the lexer position to the comma/closer, but does not consume it.
  fun readEntryKeyRaw(): Pair<String, Int>? {
    skipWsAndComments()
    val startOffset = pos
    val ch = peek() ?: return null
    // If braced or quoted, read with helpers
    if (ch == '{') {
      val pair = readBracedString() ?: return null
      // We've consumed the closing '}', so we are now positioned after it; back up one char so parse layer can see the comma normally.
      // However, parse layer expects to consume comma next; skipWs ensures nextToken sees the comma.
      skipWsAndComments()
      return Pair(pair.first.trim(), pair.second)
    }
    if (ch == '"') {
      val pair = readQuotedString() ?: return null
      skipWsAndComments()
      return Pair(pair.first.trim(), pair.second)
    }
    // Otherwise, read until comma or closer or whitespace
    val sb = StringBuilder()
    while (true) {
      val c = peek() ?: break
      if (c == ',' || c == '}' || c == ')' || c.isWhitespace()) break
      sb.append(c)
      advance()
    }
    skipWsAndComments()
    return Pair(sb.toString().trim(), startOffset)
  }

  // -------------------- Lossless chunk readers (Value/Blob) --------------------

  // Read a braced string into StrChunks, starting at '{', excluding the outer braces.
  fun readBracedStrChunks(): Pair<List<StrChunk>, Int>? {
    skipWsAndComments()
    if (peek() != '{') return null
    val startOffset = pos
    advance() // consume '{'
    val chunks = mutableListOf<StrChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += StrChunk.Text(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '{' -> {
          advance()
          // nested group
          val (inner, _) = readStrChunksInsideBraces()
          flushText()
          chunks += StrChunk.Group(inner)
        }
        '}' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, startOffset)
  }

  // Helper to read chunks until matching '}' (assumes '{' already consumed)
  private fun readStrChunksInsideBraces(): Pair<List<StrChunk>, Int> {
    val chunks = mutableListOf<StrChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += StrChunk.Text(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '{' -> {
          advance()
          val (inner, _) = readStrChunksInsideBraces()
          flushText()
          chunks += StrChunk.Group(inner)
        }
        '}' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, 0)
  }

  // Quoted string into StrChunks. Supports nested braced groups inside quotes.
  fun readQuotedStrChunks(): Pair<List<StrChunk>, Int>? {
    skipWsAndComments()
    if (peek() != '"') return null
    val startOffset = pos
    advance() // consume '"'
    val chunks = mutableListOf<StrChunk>()
    val sb = StringBuilder()
    var escaped = false
    fun flushText() { if (sb.isNotEmpty()) { chunks += StrChunk.Text(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      if (escaped) {
        sb.append(c)
        escaped = false
        advance()
        continue
      }
      when (c) {
        '\\' -> { escaped = true; advance() }
        '"' -> { advance(); flushText(); return Pair(chunks, startOffset) }
        '{' -> {
          advance()
          val (inner, _) = readStrChunksInsideBraces()
          flushText()
          chunks += StrChunk.Group(inner)
        }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, startOffset)
  }

  // Blob readers for @comment/@preamble bodies
  fun readBracedBlob(): Pair<List<BlobChunk>, Int>? {
    skipWsAndComments()
    if (peek() != '{') return null
    val startOffset = pos
    advance() // '{'
    val chunks = mutableListOf<BlobChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += BlobChunk.BlobText(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '{' -> {
          advance()
          val (inner, _) = readBracedBlobInner()
          flushText()
          chunks += BlobChunk.BlobGroup(Delim.BRACES, inner)
        }
        '}' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, startOffset)
  }

  private fun readBracedBlobInner(): Pair<List<BlobChunk>, Int> {
    val chunks = mutableListOf<BlobChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += BlobChunk.BlobText(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '{' -> {
          advance()
          val (inner, _) = readBracedBlobInner()
          flushText()
          chunks += BlobChunk.BlobGroup(Delim.BRACES, inner)
        }
        '}' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, 0)
  }

  fun readParenBlob(): Pair<List<BlobChunk>, Int>? {
    skipWsAndComments()
    if (peek() != '(') return null
    val startOffset = pos
    advance() // '('
    val chunks = mutableListOf<BlobChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += BlobChunk.BlobText(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '(' -> {
          advance()
          val (inner, _) = readParenBlobInner()
          flushText()
          chunks += BlobChunk.BlobGroup(Delim.PARENS, inner)
        }
        ')' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, startOffset)
  }

  private fun readParenBlobInner(): Pair<List<BlobChunk>, Int> {
    val chunks = mutableListOf<BlobChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += BlobChunk.BlobText(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '(' -> {
          advance()
          val (inner, _) = readParenBlobInner()
          flushText()
          chunks += BlobChunk.BlobGroup(Delim.PARENS, inner)
        }
        ')' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, 0)
  }

  // After-open variants: assume the opening delimiter has been consumed by the token stream.
  fun readBracedStrChunksAfterOpen(): Pair<List<StrChunk>, Int> {
    val startOffset = (pos - 1).coerceAtLeast(0)
    val chunks = mutableListOf<StrChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += StrChunk.Text(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '{' -> { advance(); val (inner, _) = readStrChunksInsideBraces(); flushText(); chunks += StrChunk.Group(inner) }
        '}' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, startOffset)
  }

  fun readBracedBlobAfterOpen(): Pair<List<BlobChunk>, Int> {
    val startOffset = (pos - 1).coerceAtLeast(0)
    val chunks = mutableListOf<BlobChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += BlobChunk.BlobText(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '{' -> { advance(); val (inner, _) = readBracedBlobInner(); flushText(); chunks += BlobChunk.BlobGroup(Delim.BRACES, inner) }
        '(' -> { advance(); val (inner, _) = readParenBlobInner(); flushText(); chunks += BlobChunk.BlobGroup(Delim.PARENS, inner) }
        '}' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, startOffset)
  }

  fun readParenBlobAfterOpen(): Pair<List<BlobChunk>, Int> {
    val startOffset = (pos - 1).coerceAtLeast(0)
    val chunks = mutableListOf<BlobChunk>()
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { chunks += BlobChunk.BlobText(sb.toString()); sb.setLength(0) } }
    while (true) {
      val c = peek() ?: break
      when (c) {
        '(' -> { advance(); val (inner, _) = readParenBlobInner(); flushText(); chunks += BlobChunk.BlobGroup(Delim.PARENS, inner) }
        '{' -> { advance(); val (inner, _) = readBracedBlobInner(); flushText(); chunks += BlobChunk.BlobGroup(Delim.BRACES, inner) }
        ')' -> { advance(); break }
        else -> { sb.append(c); advance() }
      }
    }
    flushText()
    return Pair(chunks, startOffset)
  }

  // Read entry key after opener has been consumed by tokenization. Reads until comma/closer/whitespace.
  fun readEntryKeyAfterOpener(): Pair<String, Int> {
    val startOffset = pos
    val sb = StringBuilder()
    while (true) {
      val c = peek() ?: break
      if (c == ',' || c == '}' || c == ')' || c.isWhitespace()) break
      sb.append(c)
      advance()
    }
    return Pair(sb.toString().trim(), startOffset)
  }
}
