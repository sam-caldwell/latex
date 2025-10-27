package net.samcaldwell.latex.bibtex

class BibParser(private val source: String) {
  private val lx = BibLexer(source)
  private var lookahead: Token = lx.nextToken()

  data class ParseResult(val file: BibFile, val errors: List<ParserError>)

  private val errors = mutableListOf<ParserError>()

  fun toLineCol(offset: Int): Pair<Int, Int> {
    // Quick line/col recompute for external use
    var line = 1
    var col = 1
    var i = 0
    while (i < source.length && i < offset) {
      val c = source[i]
      if (c == '\n') { line++; col = 1 } else col++
      i++
    }
    return line to col
  }

  private fun consume(type: TokType): Token {
    val t = lookahead
    if (t.type != type) {
      errors += ParserError("Expected $type but found ${t.type}", t.offset)
    } else {
      lookahead = lx.nextToken()
    }
    return t
  }

  private fun match(type: TokType): Boolean = lookahead.type == type

  fun parse(): ParseResult {
    val nodes = mutableListOf<BibNode>()
    while (lookahead.type != TokType.EOF) {
      if (match(TokType.AT)) {
        nodes += parseAtBlock()
      } else {
        lookahead = lx.nextToken()
      }
    }
    return ParseResult(BibFile(nodes), errors)
  }

  private fun parseAtBlock(): BibNode {
    val atTok = consume(TokType.AT)
    val typeTok = if (match(TokType.IDENT)) consume(TokType.IDENT) else run {
      errors += ParserError("Missing type after @", lookahead.offset); lookahead
    }
    // Determine delimiter without consuming
    val delim: Delim = when {
      match(TokType.LBRACE) -> Delim.BRACES
      match(TokType.LPAREN) -> Delim.PARENS
      else -> { errors += ParserError("Missing '{' or '(' after @${typeTok.text}", lookahead.offset); Delim.BRACES }
    }

    val type = typeTok.text.lowercase()
    return when (type) {
      "string" -> parseStringDirective(atTok.offset, delim)
      "preamble" -> parsePreambleDirective(atTok.offset, delim)
      "comment" -> parseCommentDirective(atTok.offset, delim)
      else -> parseEntry(typeTok.text, atTok.offset, delim)
    }
  }

  private fun parseStringDirective(startOffset: Int, delim: Delim): BibNode {
    // Expect opener
    when (delim) {
      Delim.BRACES -> if (match(TokType.LBRACE)) consume(TokType.LBRACE) else errors += ParserError("Missing '{' after @string", lookahead.offset)
      Delim.PARENS -> if (match(TokType.LPAREN)) consume(TokType.LPAREN) else errors += ParserError("Missing '(' after @string", lookahead.offset)
    }
    val nameTok = if (match(TokType.IDENT)) consume(TokType.IDENT) else run {
      errors += ParserError("@string requires identifier name", lookahead.offset); lookahead
    }
    if (match(TokType.EQUALS)) consume(TokType.EQUALS) else errors += ParserError("Missing '=' in @string", lookahead.offset)
    val (value, _) = parseValue()
    // Optional trailing comma(s)
    while (match(TokType.COMMA)) consume(TokType.COMMA)
    // Expect closer
    when (delim) {
      Delim.BRACES -> if (match(TokType.RBRACE)) consume(TokType.RBRACE) else errors += ParserError("Missing '}' for @string", lookahead.offset)
      Delim.PARENS -> if (match(TokType.RPAREN)) consume(TokType.RPAREN) else errors += ParserError("Missing ')' for @string", lookahead.offset)
    }
    return BibNode.StringDirective(nameTok.text, value, startOffset, delim)
  }

  private fun parsePreambleDirective(startOffset: Int, delim: Delim): BibNode {
    val chunks: List<BlobChunk> = when (delim) {
      Delim.BRACES -> {
        val (ch, _) = lx.readBracedBlobAfterOpen()
        lookahead = lx.nextToken(); ch
      }
      Delim.PARENS -> {
        val (ch, _) = lx.readParenBlobAfterOpen()
        lookahead = lx.nextToken(); ch
      }
    }
    return BibNode.PreambleDirective(Blob(delim, chunks), startOffset, delim)
  }

  private fun parseCommentDirective(startOffset: Int, delim: Delim): BibNode {
    val chunks: List<BlobChunk> = when (delim) {
      Delim.BRACES -> { val (ch, _) = lx.readBracedBlobAfterOpen(); lookahead = lx.nextToken(); ch }
      Delim.PARENS -> { val (ch, _) = lx.readParenBlobAfterOpen(); lookahead = lx.nextToken(); ch }
    }
    return BibNode.CommentDirective(Blob(delim, chunks), startOffset, delim)
  }

  private fun parseEntry(typeText: String, headerOffset: Int, delim: Delim): BibNode {
    // Opening delimiter offset then consume opener token
    val openOff = lookahead.offset
    when (delim) {
      Delim.BRACES -> if (match(TokType.LBRACE)) consume(TokType.LBRACE) else errors += ParserError("Missing '{' after @${typeText}", lookahead.offset)
      Delim.PARENS -> if (match(TokType.LPAREN)) consume(TokType.LPAREN) else errors += ParserError("Missing '(' after @${typeText}", lookahead.offset)
    }
    // key from source between opener and first comma/closer
    val key = extractKeyFromSource(openOff, delim) ?: run {
      errors += ParserError("Missing entry key", lookahead.offset)
      ""
    }
    // Fast forward tokens to comma after key, then consume
    while (!(match(TokType.COMMA) || match(TokType.RBRACE) || match(TokType.RPAREN) || lookahead.type == TokType.EOF)) {
      lookahead = lx.nextToken()
    }
    if (match(TokType.COMMA)) consume(TokType.COMMA) else errors += ParserError("Missing comma after entry key", lookahead.offset)

    val fields = mutableListOf<Field>()
    // Field list until closing brace/paren
    while (!match(TokType.RBRACE) && !match(TokType.RPAREN) && lookahead.type != TokType.EOF) {
      // Allow trailing commas
      if (match(TokType.COMMA)) { consume(TokType.COMMA); continue }
      if (!match(TokType.IDENT)) {
        // Graceful recovery: allow stray commas or early closers without error; otherwise skip until a sensible boundary
        if (match(TokType.RBRACE) || match(TokType.RPAREN) || lookahead.type == TokType.EOF) break
        if (match(TokType.COMMA)) { consume(TokType.COMMA); continue }
        // Skip ahead to next IDENT/COMMA/closer to avoid false positives inside braced text
        var steps = 0
        while (!(match(TokType.IDENT) || match(TokType.COMMA) || match(TokType.RBRACE) || match(TokType.RPAREN) || lookahead.type == TokType.EOF) && steps < 1024) {
          lookahead = lx.nextToken(); steps++
        }
        if (match(TokType.COMMA)) { consume(TokType.COMMA); continue }
        if (!match(TokType.IDENT)) break
      }
      val nameTok = consume(TokType.IDENT)
      if (match(TokType.EQUALS)) {
        consume(TokType.EQUALS)
      } else {
        // Be resilient: allow implicit '=' when next token clearly begins a value.
        // This avoids false positives in the presence of odd whitespace or lexer quirks.
        val valueLikely = lookahead.type in setOf(TokType.LBRACE, TokType.QUOTED_STRING, TokType.NUMBER, TokType.IDENT)
        if (!valueLikely) {
          // As a last resort, inspect raw slice for '='
          val nameEnd = nameTok.offset + nameTok.text.length
          val nextOff = lookahead.offset
          val sliceHasEq = try {
            if (nextOff > nameEnd && nextOff <= source.length) source.substring(nameEnd, nextOff).any { it == '=' } else false
          } catch (_: Throwable) { false }
          if (!sliceHasEq) errors += ParserError("Missing '=' after field name", lookahead.offset)
        }
        // If valueLikely, proceed without consuming '='; parseValueExpr will read starting at current lookahead.
      }
      val (value, valueOffset) = parseValue()
      fields += Field(nameTok.text, value, nameTok.offset, valueOffset)
      // Optional comma after each field
      if (match(TokType.COMMA)) consume(TokType.COMMA)
    }
    // consume closer
    when (delim) {
      Delim.BRACES -> if (match(TokType.RBRACE)) consume(TokType.RBRACE) else errors += ParserError("Missing '}' to close @${typeText}", lookahead.offset)
      Delim.PARENS -> if (match(TokType.RPAREN)) consume(TokType.RPAREN) else errors += ParserError("Missing ')' to close @${typeText}", lookahead.offset)
    }
    return BibNode.Entry(typeText, key, fields, headerOffset, delim)
  }

  private fun parseValue(): Pair<Value, Int> {
    val parts = mutableListOf<Part>()
    var valueOffset = lookahead.offset
    fun addPart(p: Part, off: Int) {
      if (parts.isEmpty()) valueOffset = off
      parts += p
    }
    // one or more terms separated by #
    var first = true
    while (true) {
      if (!first && match(TokType.HASH)) consume(TokType.HASH) else if (!first) break
      first = false
      when {
        match(TokType.QUOTED_STRING) -> {
          val t = consume(TokType.QUOTED_STRING)
          val chunks = parseChunksFromBraceText(t.text)
          addPart(Part.Str(StrKind.QUOTED, chunks), t.offset)
        }
        match(TokType.LBRACE) -> {
          val (chunks, off) = lx.readBracedStrChunksAfterOpen()
          lookahead = lx.nextToken()
          addPart(Part.Str(StrKind.BRACED, chunks), off)
        }
        match(TokType.IDENT) -> {
          val t = consume(TokType.IDENT)
          addPart(Part.IdentRef(t.text), t.offset)
        }
        match(TokType.NUMBER) -> {
          val t = consume(TokType.NUMBER)
          addPart(Part.Number(t.text), t.offset)
        }
        else -> {
          errors += ParserError("Invalid value expression", lookahead.offset)
          break
        }
      }
      // Allow subsequent concatenations like "a" # b # {c}
      if (!match(TokType.HASH)) break
    }
    return Pair(Value(parts), valueOffset)
  }

  companion object {
    fun flattenValue(v: Value): String {
      val sb = StringBuilder()
      appendFlattened(sb, v, emptyMap())
      // Normalize newlines/indentation to single spaces
      val unified = sb.toString().replace("\r\n", "\n").replace('\r', '\n')
      return unified.replace(Regex("[ \t]*\n+[ \t]*"), " ").trim()
    }

    fun flattenValueWith(v: Value, strings: Map<String, String>): String {
      val sb = StringBuilder()
      appendFlattened(sb, v, strings)
      val unified = sb.toString().replace("\r\n", "\n").replace('\r', '\n')
      return unified.replace(Regex("[ \t]*\n+[ \t]*"), " ").trim()
    }

    private fun appendFlattened(sb: StringBuilder, v: Value, strings: Map<String, String>) {
      for (p in v.parts) {
        when (p) {
          is Part.Number -> sb.append(p.lexeme)
          is Part.IdentRef -> sb.append(strings[p.name] ?: p.name)
          is Part.Str -> when (p.kind) {
            StrKind.BRACED, StrKind.QUOTED -> appendChunks(sb, p.chunks)
          }
        }
      }
    }

    private fun appendChunks(sb: StringBuilder, chunks: List<StrChunk>) {
      for (c in chunks) when (c) {
        is StrChunk.Text -> sb.append(c.raw)
        is StrChunk.Group -> { sb.append('{'); appendChunks(sb, c.chunks); sb.append('}') }
      }
    }

  }

  private fun extractKeyFromSource(openOffset: Int, delim: Delim): String? {
    val start = openOffset + 1
    var i = start
    val closeChar = if (delim == Delim.BRACES) '}' else ')'
    while (i < source.length) {
      val ch = source[i]
      if (ch == ',' || ch == closeChar) break
      i++
    }
    if (i <= start) return null
    return source.substring(start, i).trim()
  }

  private fun extractBodyFromSource(openOffset: Int, delim: Delim): String {
    val start = openOffset + 1
    var i = start
    var depth = 0
    val openChar = if (delim == Delim.BRACES) '{' else '('
    val closeChar = if (delim == Delim.BRACES) '}' else ')'
    while (i < source.length) {
      val ch = source[i]
      if (ch == openChar) depth++
      else if (ch == closeChar) { if (depth == 0) break else depth-- }
      i++
    }
    return if (i <= start) "" else source.substring(start, i)
  }

  private fun extractBracedInnerFromSource(openOffset: Int): String {
    val start = openOffset + 1
    var i = start
    var depth = 0
    while (i < source.length) {
      val ch = source[i]
      if (ch == '{') depth++ else if (ch == '}') { if (depth == 0) break else depth-- }
      i++
    }
    return if (i <= start) "" else source.substring(start, i)
  }
  private fun parseChunksFromBraceText(s: String): List<StrChunk> {
    val root = mutableListOf<StrChunk>()
    val stack = java.util.ArrayDeque<MutableList<StrChunk>>()
    stack.addLast(root)
    val sb = StringBuilder()
    fun flushText() { if (sb.isNotEmpty()) { stack.peekLast().add(StrChunk.Text(sb.toString())); sb.setLength(0) } }
    for (ch in s) {
      when (ch) {
        '{' -> { flushText(); stack.addLast(mutableListOf()) }
        '}' -> {
          flushText()
          if (stack.size > 1) {
            val inner = stack.removeLast()
            stack.peekLast().add(StrChunk.Group(inner))
          } else {
            // unmatched close; literal
            stack.peekLast().add(StrChunk.Text("}"))
          }
        }
        else -> sb.append(ch)
      }
    }
    flushText()
    while (stack.size > 1) {
      val inner = stack.removeLast()
      val text = StringBuilder("{")
      appendChunks(text, inner)
      stack.peekLast().add(StrChunk.Text(text.toString()))
    }
    return root
  }
}
