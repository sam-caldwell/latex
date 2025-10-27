package net.samcaldwell.latex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BibParserGrammarTest {
  @Test
  fun `comment directive supports parentheses body without extra closer error`() {
    val text = """
      @comment(This is (a) comment with (nested) parens)
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    // One node: CommentDirective
    assertEquals(1, parsed.file.nodes.size)
    val node = parsed.file.nodes.first()
    assertTrue(node is net.samcaldwell.latex.bibtex.BibNode.CommentDirective)
    val c = node as net.samcaldwell.latex.bibtex.BibNode.CommentDirective
    // Flatten blob payload to text
    fun flattenBlob(chunks: List<net.samcaldwell.latex.bibtex.BlobChunk>): String {
      val sb = StringBuilder()
      for (ch in chunks) when (ch) {
        is net.samcaldwell.latex.bibtex.BlobChunk.BlobText -> sb.append(ch.raw)
        is net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup -> sb.append(flattenBlob(ch.chunks))
      }
      return sb.toString()
    }
    assertEquals("This is (a) comment with (nested) parens", flattenBlob(c.payload.chunks))
    // No "Missing closing" error
    val noCloserError = parsed.errors.none { it.message.contains("Missing closing brace/paren") }
    assertTrue(noCloserError, "Parser reported missing closer for @comment: ${parsed.errors}")
  }

  @Test
  fun `entry key allows punctuation like colon and slash`() {
    val text = """
      @article{doe:2020/abc-01, title = {X},}
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    val entry = parsed.file.nodes.firstOrNull() as? net.samcaldwell.latex.bibtex.BibNode.Entry
    assertNotNull(entry)
    assertEquals("doe:2020/abc-01", entry!!.key)
  }

  @Test
  fun `string directive records delimiter and value flattens`() {
    val text = """
      @string(foo = {A {B} C})
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    val node = parsed.file.nodes.firstOrNull() as? net.samcaldwell.latex.bibtex.BibNode.StringDirective
    assertNotNull(node)
    assertEquals(net.samcaldwell.latex.bibtex.Delim.PARENS, node!!.delim)
    val flat = net.samcaldwell.latex.bibtex.BibParser.flattenValue(node.value)
    assertEquals("A {B} C", flat)
  }
}
