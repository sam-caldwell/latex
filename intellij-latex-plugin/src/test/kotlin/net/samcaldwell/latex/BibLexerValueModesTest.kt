package net.samcaldwell.latex

import net.samcaldwell.latex.syntax.BibHighlightingLexer
import net.samcaldwell.latex.syntax.BibTokenTypes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BibLexerValueModesTest {
  private fun lex(text: String): List<Pair<Int, Any?>> {
    val lx = BibHighlightingLexer()
    lx.start(text, 0, text.length, 0)
    val out = mutableListOf<Pair<Int, Any?>>()
    while (true) {
      val tt = lx.tokenType ?: break
      out += Pair(lx.tokenStart, tt as Any?)
      val prevEnd = lx.tokenEnd
      lx.advance()
      // Guard: ensure progress
      if (lx.tokenType != null && lx.tokenStart == prevEnd && lx.tokenEnd == prevEnd) {
        fail<Unit>("Lexer did not advance at offset $prevEnd for token $tt")
      }
    }
    return out
  }

  private fun types(text: String): List<Any?> = lex(text).map { it.second }

  @Test
  fun `title braced content is single token`() {
    val bib = "title = {Hello {Nested} World},"
    val ts = types(bib)
    // Expect LBRACE, TITLE_VALUE, RBRACE in that order for the value body
    val iBrace = ts.indexOf(BibTokenTypes.LBRACE)
    assertTrue(iBrace >= 0)
    assertEquals(BibTokenTypes.TITLE_VALUE, ts.getOrNull(iBrace + 1))
    assertEquals(BibTokenTypes.RBRACE, ts.getOrNull(iBrace + 2))
  }

  @Test
  fun `abstract braced content is single token`() {
    val bib = "abstract = {Alpha {beta} gamma},"
    val ts = types(bib)
    val iBrace = ts.indexOf(BibTokenTypes.LBRACE)
    assertTrue(iBrace >= 0)
    assertEquals(BibTokenTypes.ABSTRACT_VALUE, ts.getOrNull(iBrace + 1))
    assertEquals(BibTokenTypes.RBRACE, ts.getOrNull(iBrace + 2))
  }

  @Test
  fun `journal publisher verified_by source howpublished are single tokens`() {
    val fields = listOf(
      "journaltitle" to BibTokenTypes.TITLE_VALUE,
      "journal" to BibTokenTypes.TITLE_VALUE,
      "publisher" to BibTokenTypes.TITLE_VALUE,
      "verified_by" to BibTokenTypes.TITLE_VALUE,
      "source" to BibTokenTypes.SOURCE_VALUE,
      "howpublished" to BibTokenTypes.TITLE_VALUE,
    )
    for ((name, expectedToken) in fields) {
      val bib = "$name = {One {Two} Three},"
      val ts = types(bib)
      val iBrace = ts.indexOf(BibTokenTypes.LBRACE)
      assertTrue(iBrace >= 0, "Missing LBRACE for $name")
      assertEquals(expectedToken, ts.getOrNull(iBrace + 1), "Expected single token for $name")
      assertEquals(BibTokenTypes.RBRACE, ts.getOrNull(iBrace + 2))
    }
  }

  @Test
  fun `author top-level and split`() {
    val bib = "author = {Doe, John and {NASA} and Roe, Jane},"
    val ts = types(bib)
    assertTrue(ts.contains(BibTokenTypes.AUTHOR_AND))
    assertTrue(ts.contains(BibTokenTypes.AUTHOR_NAME))
  }

  @Test
  fun `date like numbers and delimiters`() {
    val bib = "created = {2025-10-24T01:10:31Z},"
    val ts = types(bib)
    assertTrue(ts.contains(BibTokenTypes.DATE_NUMBER))
    assertTrue(ts.contains(BibTokenTypes.DATE_DELIM))
  }
}
