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
  private var inAbstractValue: Boolean = false
  private var abstractDepth: Int = 0
  private var lastFieldWasTitle: Boolean = false
  private var expectTitleValue: Boolean = false
  private var inTitleValue: Boolean = false
  private var titleDepth: Int = 0
  private var lastFieldWasUrl: Boolean = false
  private var expectUrlValue: Boolean = false
  private var inUrlValue: Boolean = false
  private var urlDepth: Int = 0
  private var lastFieldWasBool: Boolean = false
  private var expectBoolValue: Boolean = false
  private var inBoolValue: Boolean = false
  private var boolDepth: Int = 0
  private var lastFieldWasSource: Boolean = false
  private var expectSourceValue: Boolean = false
  private var inSourceValue: Boolean = false
  private var sourceDepth: Int = 0
  private var lastFieldWasKeywords: Boolean = false
  private var expectKeywordsValue: Boolean = false
  private var inKeywordsValue: Boolean = false
  private var keywordsDepth: Int = 0
  private var lastFieldWasDateLike: Boolean = false
  private var expectDateLikeValue: Boolean = false
  private var inDateLikeValue: Boolean = false
  private var dateLikeDepth: Int = 0
  private var lastFieldWasDoi: Boolean = false
  private var expectDoiValue: Boolean = false
  private var inDoiValue: Boolean = false
  private var doiDepth: Int = 0
  private var lastFieldWasPages: Boolean = false
  private var expectPagesValue: Boolean = false
  private var inPagesValue: Boolean = false
  private var pagesDepth: Int = 0
  private var lastFieldWasVolume: Boolean = false
  private var expectVolumeValue: Boolean = false
  private var inVolumeValue: Boolean = false
  private var volumeDepth: Int = 0
  private var lastFieldWasIsbn: Boolean = false
  private var expectIsbnValue: Boolean = false
  private var inIsbnValue: Boolean = false
  private var isbnDepth: Int = 0
  private var lastFieldWasAuthor: Boolean = false
  private var expectAuthorValue: Boolean = false
  private var inAuthorValue: Boolean = false
  private var authorDepth: Int = 0
  private var expectHeaderKeyOpen: Boolean = false
  private var inHeaderKey: Boolean = false

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.start = startOffset
    this.end = endOffset
    this.tokenStart = startOffset
    this.tokenEnd = startOffset
    this.tokenType = null
    this.lastFieldWasAbstract = false
    this.expectAbstractValue = false
    this.inAbstractValue = false
    this.abstractDepth = 0
    this.lastFieldWasTitle = false
    this.expectTitleValue = false
    this.inTitleValue = false
    this.titleDepth = 0
    this.lastFieldWasUrl = false
    this.expectUrlValue = false
    this.inUrlValue = false
    this.urlDepth = 0
    this.lastFieldWasBool = false
    this.expectBoolValue = false
    this.inBoolValue = false
    this.boolDepth = 0
    this.lastFieldWasSource = false
    this.expectSourceValue = false
    this.inSourceValue = false
    this.sourceDepth = 0
    this.lastFieldWasKeywords = false
    this.expectKeywordsValue = false
    this.inKeywordsValue = false
    this.keywordsDepth = 0
    this.lastFieldWasDateLike = false
    this.expectDateLikeValue = false
    this.inDateLikeValue = false
    this.dateLikeDepth = 0
    this.lastFieldWasDoi = false
    this.expectDoiValue = false
    this.inDoiValue = false
    this.doiDepth = 0
    this.lastFieldWasPages = false
    this.expectPagesValue = false
    this.inPagesValue = false
    this.pagesDepth = 0
    this.lastFieldWasVolume = false
    this.expectVolumeValue = false
    this.inVolumeValue = false
    this.volumeDepth = 0
    this.lastFieldWasIsbn = false
    this.expectIsbnValue = false
    this.inIsbnValue = false
    this.isbnDepth = 0
    this.lastFieldWasAuthor = false
    this.expectAuthorValue = false
    this.inAuthorValue = false
    this.authorDepth = 0
    this.expectHeaderKeyOpen = false
    this.inHeaderKey = false
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

    // Special handling for entry key after @type{key,
    if (inHeaderKey) {
      if (ch == ',') { tokenEnd = i + 1; tokenType = BibTokenTypes.COMMA; inHeaderKey = false; return }
      if (ch == '}' || ch == ')') { tokenEnd = i + 1; tokenType = if (ch == '}') BibTokenTypes.RBRACE else BibTokenTypes.RPAREN; inHeaderKey = false; return }
      if (ch.isWhitespace()) {
        var j = i
        while (j < end && buffer[j].isWhitespace()) j++
        tokenEnd = j; tokenType = TokenType.WHITE_SPACE; return
      }
      var j = i
      while (j < end) {
        val c = buffer[j]
        if (c == ',' || c == '}' || c == ')' || c.isWhitespace()) break
        j++
      }
      tokenEnd = j
      tokenType = BibTokenTypes.KEY
      return
    }

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

    // Special handling when inside abstract value: emit braces separately and content as ABSTRACT_VALUE
    if (inAbstractValue) {
      when (ch) {
        '{' -> { abstractDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { abstractDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (abstractDepth <= 0) { inAbstractValue = false; lastFieldWasAbstract = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.ABSTRACT_VALUE
      return
    }

    // Special handling when inside DOI value: braces white, DOI text green
    if (inDoiValue) {
      when (ch) {
        '{' -> { doiDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { doiDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (doiDepth <= 0) { inDoiValue = false; lastFieldWasDoi = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.DOI_VALUE
      return
    }

    // Special handling when inside author value: braces white; top-level 'and' light blue; names green
    if (inAuthorValue) {
      when (ch) {
        '{' -> { authorDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { authorDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (authorDepth <= 0) { inAuthorValue = false; lastFieldWasAuthor = false }; return }
      }
      // Detect top-level 'and' keyword with word boundaries
      if (authorDepth <= 1 && (ch == 'a' || ch == 'A')) {
        if (i + 3 <= end) {
          val sub = buffer.subSequence(i, i + 3).toString()
          if (sub.equals("and", ignoreCase = true)) {
            val prev = if (i - 1 >= start) buffer[i - 1] else ' '
            val next = if (i + 3 < end) buffer[i + 3] else ' '
            val prevWs = prev.isWhitespace() || prev == '{' || prev == ','
            val nextWs = next.isWhitespace() || next == '}' || next == ','
            if (prevWs && nextWs) {
              tokenEnd = i + 3
              tokenType = BibTokenTypes.AUTHOR_AND
              return
            }
          }
        }
      }
      // Emit name chunk up to next brace or top-level 'and'
      var j = i
      while (j < end) {
        val c = buffer[j]
        if (c == '{' || c == '}') break
        if (authorDepth <= 1 && (c == 'a' || c == 'A')) {
          if (j + 3 <= end) {
            val sub = buffer.subSequence(j, j + 3).toString()
            if (sub.equals("and", ignoreCase = true)) break
          }
        }
        j++
      }
      tokenEnd = j
      tokenType = BibTokenTypes.AUTHOR_NAME
      return
    }

    // Special handling when inside pages value: braces white, pages text blue
    if (inPagesValue) {
      when (ch) {
        '{' -> { pagesDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { pagesDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (pagesDepth <= 0) { inPagesValue = false; lastFieldWasPages = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.PAGES_VALUE
      return
    }

    // Special handling when inside volume value: braces white, volume text blue
    if (inVolumeValue) {
      when (ch) {
        '{' -> { volumeDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { volumeDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (volumeDepth <= 0) { inVolumeValue = false; lastFieldWasVolume = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.VOLUME_VALUE
      return
    }

    // Special handling when inside ISBN value: braces white, ISBN text blue
    if (inIsbnValue) {
      when (ch) {
        '{' -> { isbnDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { isbnDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (isbnDepth <= 0) { inIsbnValue = false; lastFieldWasIsbn = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.ISBN_VALUE
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

    // Special handling when inside bool value: emit braces separately and content as BOOL_VALUE
    if (inBoolValue) {
      when (ch) {
        '{' -> { boolDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { boolDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (boolDepth <= 0) { inBoolValue = false; lastFieldWasBool = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.BOOL_VALUE
      return
    }

    // Special handling when inside source value: emit braces separately and content as SOURCE_VALUE
    if (inSourceValue) {
      when (ch) {
        '{' -> { sourceDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { sourceDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (sourceDepth <= 0) { inSourceValue = false; lastFieldWasSource = false }; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.SOURCE_VALUE
      return
    }

    // Special handling when inside keywords value: braces white, commas white, keywords text green
    if (inKeywordsValue) {
      when (ch) {
        '{' -> { keywordsDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { keywordsDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (keywordsDepth <= 0) { inKeywordsValue = false; lastFieldWasKeywords = false }; return }
        ',' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.COMMA; return }
      }
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}' || c == ',') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.KEYWORDS_VALUE
      return
    }

    // Special handling when inside date-like value: braces white, '-' and ':' white, numbers blue
    if (inDateLikeValue) {
      when (ch) {
        '{' -> { dateLikeDepth++; tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return }
        '}' -> { dateLikeDepth--; tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; if (dateLikeDepth <= 0) { inDateLikeValue = false; lastFieldWasDateLike = false }; return }
        '-' , ':' , 'T' , 'Z' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.DATE_DELIM; return }
      }
      if (ch.isDigit()) {
        while (i < end && buffer[i].isDigit()) i++
        tokenEnd = i
        tokenType = BibTokenTypes.DATE_NUMBER
        return
      }
      // Emit other content up to next special
      while (i < end) {
        val c = buffer[i]
        if (c == '{' || c == '}' || c == '-' || c == ':') break
        i++
      }
      tokenEnd = i
      tokenType = BibTokenTypes.IDENT
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
      '=' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.EQUALS; if (lastFieldWasAbstract) expectAbstractValue = true; if (lastFieldWasTitle) expectTitleValue = true; if (lastFieldWasUrl) expectUrlValue = true; if (lastFieldWasBool) expectBoolValue = true; if (lastFieldWasSource) expectSourceValue = true; if (lastFieldWasKeywords) expectKeywordsValue = true; if (lastFieldWasDateLike) expectDateLikeValue = true; if (lastFieldWasDoi) expectDoiValue = true; if (lastFieldWasPages) expectPagesValue = true; if (lastFieldWasVolume) expectVolumeValue = true; if (lastFieldWasIsbn) expectIsbnValue = true; if (lastFieldWasAuthor) expectAuthorValue = true; return }
      ',' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.COMMA; lastFieldWasAbstract = false; expectAbstractValue = false; lastFieldWasTitle = false; expectTitleValue = false; lastFieldWasUrl = false; expectUrlValue = false; lastFieldWasBool = false; expectBoolValue = false; lastFieldWasSource = false; expectSourceValue = false; lastFieldWasKeywords = false; expectKeywordsValue = false; lastFieldWasDateLike = false; expectDateLikeValue = false; lastFieldWasDoi = false; expectDoiValue = false; lastFieldWasPages = false; expectPagesValue = false; lastFieldWasVolume = false; expectVolumeValue = false; lastFieldWasIsbn = false; expectIsbnValue = false; lastFieldWasAuthor = false; expectAuthorValue = false; return }
      '{' -> {
        if (expectHeaderKeyOpen) {
          expectHeaderKeyOpen = false
          inHeaderKey = true
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        }
        if (expectAbstractValue) {
          // Enter abstract value mode
          inAbstractValue = true
          abstractDepth = 1
          expectAbstractValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectTitleValue) {
          // Enter title value mode: emit this brace, then TITLE_VALUE/BRACE tokens until closing
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
        } else if (expectBoolValue) {
          // Enter bool value mode
          inBoolValue = true
          boolDepth = 1
          expectBoolValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectSourceValue) {
          // Enter source value mode
          inSourceValue = true
          sourceDepth = 1
          expectSourceValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectKeywordsValue) {
          // Enter keywords value mode
          inKeywordsValue = true
          keywordsDepth = 1
          expectKeywordsValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectDateLikeValue) {
          // Enter date-like value mode
          inDateLikeValue = true
          dateLikeDepth = 1
          expectDateLikeValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectDoiValue) {
          // Enter DOI value mode
          inDoiValue = true
          doiDepth = 1
          expectDoiValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectPagesValue) {
          // Enter pages value mode
          inPagesValue = true
          pagesDepth = 1
          expectPagesValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectVolumeValue) {
          // Enter volume value mode
          inVolumeValue = true
          volumeDepth = 1
          expectVolumeValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectIsbnValue) {
          // Enter isbn value mode
          inIsbnValue = true
          isbnDepth = 1
          expectIsbnValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else if (expectAuthorValue) {
          // Enter author value mode
          inAuthorValue = true
          authorDepth = 1
          expectAuthorValue = false
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LBRACE
          return
        } else {
          tokenEnd = i + 1; tokenType = BibTokenTypes.LBRACE; return
        }
      }
      '}' -> { tokenEnd = i + 1; tokenType = BibTokenTypes.RBRACE; return }
      '(' -> {
        if (expectHeaderKeyOpen) {
          expectHeaderKeyOpen = false
          inHeaderKey = true
          tokenEnd = i + 1
          tokenType = BibTokenTypes.LPAREN
          return
        }
        tokenEnd = i + 1; tokenType = BibTokenTypes.LPAREN; return }
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
        } else if (expectBoolValue) {
          tokenType = BibTokenTypes.BOOL_VALUE
          expectBoolValue = false
          lastFieldWasBool = false
        } else if (expectSourceValue) {
          tokenType = BibTokenTypes.SOURCE_VALUE
          expectSourceValue = false
          lastFieldWasSource = false
        } else if (expectKeywordsValue) {
          tokenType = BibTokenTypes.KEYWORDS_VALUE
          expectKeywordsValue = false
          lastFieldWasKeywords = false
        } else if (expectDoiValue) {
          tokenType = BibTokenTypes.DOI_VALUE
          expectDoiValue = false
          lastFieldWasDoi = false
        } else if (expectPagesValue) {
          tokenType = BibTokenTypes.PAGES_VALUE
          expectPagesValue = false
          lastFieldWasPages = false
        } else if (expectVolumeValue) {
          tokenType = BibTokenTypes.VOLUME_VALUE
          expectVolumeValue = false
          lastFieldWasVolume = false
        } else if (expectIsbnValue) {
          tokenType = BibTokenTypes.ISBN_VALUE
          expectIsbnValue = false
          lastFieldWasIsbn = false
        } else if (expectAuthorValue) {
          tokenType = BibTokenTypes.AUTHOR_NAME
          expectAuthorValue = false
          lastFieldWasAuthor = false
        } else if (expectDateLikeValue) {
          // For quoted dates, emit the full content as IDENT; numbers blue within quotes not split here
          tokenType = BibTokenTypes.IDENT
          expectDateLikeValue = false
          lastFieldWasDateLike = false
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
        expectHeaderKeyOpen = true
        return
      }
      tokenEnd = i
      tokenType = type
      if (type == BibTokenTypes.FIELD_NAME) {
        val name = buffer.subSequence(tokenStart, tokenEnd).toString()
        lastFieldWasAbstract = name.equals("abstract", ignoreCase = true)
        lastFieldWasTitle = name.equals("title", ignoreCase = true)
            || name.equals("journaltitle", ignoreCase = true)
            || name.equals("journal", ignoreCase = true)
            || name.equals("publisher", ignoreCase = true)
            || name.equals("verified_by", ignoreCase = true)
            || name.equals("howpublished", ignoreCase = true)
        lastFieldWasUrl = name.equals("url", ignoreCase = true)
        lastFieldWasDoi = name.equals("doi", ignoreCase = true)
        lastFieldWasPages = name.equals("pages", ignoreCase = true)
        lastFieldWasVolume = name.equals("volume", ignoreCase = true)
        lastFieldWasIsbn = name.equals("isbn", ignoreCase = true)
        lastFieldWasAuthor = name.equals("author", ignoreCase = true)
        lastFieldWasBool = name.equals("verified", ignoreCase = true)
        lastFieldWasSource = name.equals("source", ignoreCase = true) || name.equals("type", ignoreCase = true)
        lastFieldWasKeywords = name.equals("keywords", ignoreCase = true)
        lastFieldWasDateLike = name.equals("date", ignoreCase = true)
            || name.equals("eventdate", ignoreCase = true)
            || name.equals("origdate", ignoreCase = true)
            || name.equals("urldate", ignoreCase = true)
            || name.equals("year", ignoreCase = true)
            || name.equals("created", ignoreCase = true)
            || name.equals("modified", ignoreCase = true)
            || name.equals("timestamp", ignoreCase = true)
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
