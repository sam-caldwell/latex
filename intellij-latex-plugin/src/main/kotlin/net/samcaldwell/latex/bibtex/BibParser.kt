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
    // Accept either { or (
    val ldelim = lookahead
    if (match(TokType.LBRACE)) consume(TokType.LBRACE) else if (match(TokType.LPAREN)) consume(TokType.LPAREN) else {
      errors += ParserError("Missing '{' or '(' after @${typeTok.text}", lookahead.offset)
    }

    val type = typeTok.text.lowercase()
    val node: BibNode = when (type) {
      "string" -> parseStringDirective(atTok.offset)
      "preamble" -> parsePreambleDirective(atTok.offset)
      "comment" -> parseCommentDirective(atTok.offset)
      else -> parseEntry(typeTok.text, atTok.offset)
    }

    // Closing delimiter: } or )
    if (match(TokType.RBRACE)) consume(TokType.RBRACE) else if (match(TokType.RPAREN)) consume(TokType.RPAREN) else {
      errors += ParserError("Missing closing brace/paren for @${typeTok.text}", lookahead.offset)
    }
    return node
  }

  private fun parseStringDirective(startOffset: Int): BibNode {
    // @string{ name = value }
    val nameTok = if (match(TokType.IDENT)) consume(TokType.IDENT) else run {
      errors += ParserError("@string requires identifier name", lookahead.offset); lookahead
    }
    if (match(TokType.EQUALS)) consume(TokType.EQUALS) else errors += ParserError("Missing '=' in @string", lookahead.offset)
    val (value, _) = parseValueExpr()
    // Optional trailing comma(s)
    while (match(TokType.COMMA)) consume(TokType.COMMA)
    return BibNode.StringDirective(nameTok.text, value, startOffset)
  }

  private fun parsePreambleDirective(startOffset: Int): BibNode {
    val (value, _) = parseValueExpr()
    while (match(TokType.COMMA)) consume(TokType.COMMA)
    return BibNode.PreambleDirective(value, startOffset)
  }

  private fun parseCommentDirective(startOffset: Int): BibNode {
    // Read until closing delimiter as raw text if present
    val text = StringBuilder()
    // Attempt to read braced content as one block if available
    if (lookahead.type == TokType.LBRACE) {
      val pair = lx.readBracedString()
      if (pair != null) {
        lookahead = lx.nextToken() // move after closing brace
        return BibNode.CommentDirective(pair.first, startOffset)
      }
    }
    return BibNode.CommentDirective("", startOffset)
  }

  private fun parseEntry(typeText: String, headerOffset: Int): BibNode {
    // key
    val key = when {
      match(TokType.IDENT) -> consume(TokType.IDENT).text
      match(TokType.NUMBER) -> consume(TokType.NUMBER).text
      match(TokType.QUOTED_STRING) -> consume(TokType.QUOTED_STRING).text
      match(TokType.LBRACE) -> {
        val pair = lx.readBracedString(); lookahead = lx.nextToken(); (pair?.first ?: "").trim()
      }
      else -> {
        errors += ParserError("Missing entry key", lookahead.offset)
        ""
      }
    }
    if (match(TokType.COMMA)) consume(TokType.COMMA) else errors += ParserError("Missing comma after entry key", lookahead.offset)

    val fields = mutableListOf<Field>()
    // Field list until closing brace/paren
    while (!match(TokType.RBRACE) && !match(TokType.RPAREN) && lookahead.type != TokType.EOF) {
      // Allow trailing commas
      if (match(TokType.COMMA)) { consume(TokType.COMMA); continue }
      if (!match(TokType.IDENT)) { errors += ParserError("Expected field name", lookahead.offset); break }
      val nameTok = consume(TokType.IDENT)
      if (match(TokType.EQUALS)) consume(TokType.EQUALS) else errors += ParserError("Missing '=' after field name", lookahead.offset)
      val (value, valueOffset) = parseValueExpr()
      fields += Field(nameTok.text, value, nameTok.offset, valueOffset)
      // Optional comma after each field
      if (match(TokType.COMMA)) consume(TokType.COMMA)
    }
    return BibNode.Entry(typeText, key, fields, headerOffset)
  }

  private fun parseValueExpr(): Pair<ValueExpr, Int> {
    val parts = mutableListOf<ValuePart>()
    var valueOffset = lookahead.offset
    fun addPart(p: ValuePart, off: Int) {
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
          addPart(ValuePart.Text(t.text), t.offset)
        }
        match(TokType.LBRACE) -> {
          val pair = lx.readBracedString()
          val (text, off) = if (pair != null) pair else Pair("", lookahead.offset)
          lookahead = lx.nextToken()
          addPart(ValuePart.Text(text), off)
        }
        match(TokType.IDENT) -> {
          val t = consume(TokType.IDENT)
          addPart(ValuePart.Identifier(t.text), t.offset)
        }
        match(TokType.NUMBER) -> {
          val t = consume(TokType.NUMBER)
          addPart(ValuePart.NumberLiteral(t.text), t.offset)
        }
        else -> {
          errors += ParserError("Invalid value expression", lookahead.offset)
          break
        }
      }
      // Allow subsequent concatenations like "a" # b # {c}
      if (!match(TokType.HASH)) break
    }
    return Pair(ValueExpr(parts), valueOffset)
  }

  companion object {
    fun flattenValue(v: ValueExpr): String {
      val sb = StringBuilder()
      for (p in v.parts) {
        when (p) {
          is ValuePart.Text -> sb.append(p.text)
          is ValuePart.Identifier -> sb.append(p.name) // leave macro names as-is
          is ValuePart.NumberLiteral -> sb.append(p.text)
        }
      }
      // Normalize newlines/indentation to single spaces
      val unified = sb.toString().replace("\r\n", "\n").replace('\r', '\n')
      return unified.replace(Regex("[ \t]*\n+[ \t]*"), " ").trim()
    }
  }
}

