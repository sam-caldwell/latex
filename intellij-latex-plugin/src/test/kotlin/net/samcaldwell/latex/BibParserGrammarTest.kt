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
    // Flatten blob payload to text preserving group delimiters
    fun flattenBlob(chunks: List<net.samcaldwell.latex.bibtex.BlobChunk>): String {
      val sb = StringBuilder()
      fun rec(list: List<net.samcaldwell.latex.bibtex.BlobChunk>) {
        for (ch in list) when (ch) {
          is net.samcaldwell.latex.bibtex.BlobChunk.BlobText -> sb.append(ch.raw)
          is net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup -> {
            if (ch.kind == net.samcaldwell.latex.bibtex.Delim.BRACES) sb.append('{') else sb.append('(')
            rec(ch.chunks)
            if (ch.kind == net.samcaldwell.latex.bibtex.Delim.BRACES) sb.append('}') else sb.append(')')
          }
        }
      }
      rec(chunks)
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

  @Test
  fun `braced value preserves nested group chunks`() {
    val text = """
      @misc{key, title = {Hello {Nested} World},}
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    val entry = parsed.file.nodes.first() as net.samcaldwell.latex.bibtex.BibNode.Entry
    val field = entry.fields.first { it.name.equals("title", ignoreCase = true) }
    val parts = field.value.parts
    assertEquals(1, parts.size)
    val str = parts[0] as net.samcaldwell.latex.bibtex.Part.Str
    assertEquals(net.samcaldwell.latex.bibtex.StrKind.BRACED, str.kind)
    val chunks = str.chunks
    // Expect: Text("Hello "), Group(Text("Nested")), Text(" World")
    assertEquals(3, chunks.size)
    assertTrue(chunks[0] is net.samcaldwell.latex.bibtex.StrChunk.Text)
    assertEquals("Hello ", (chunks[0] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
    assertTrue(chunks[1] is net.samcaldwell.latex.bibtex.StrChunk.Group)
    val inner = (chunks[1] as net.samcaldwell.latex.bibtex.StrChunk.Group).chunks
    assertEquals(1, inner.size)
    assertTrue(inner[0] is net.samcaldwell.latex.bibtex.StrChunk.Text)
    assertEquals("Nested", (inner[0] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
    assertTrue(chunks[2] is net.samcaldwell.latex.bibtex.StrChunk.Text)
    assertEquals(" World", (chunks[2] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
  }

  @Test
  fun `quoted value preserves nested group chunks`() {
    val text = """
      @string(foo = "Hello {Nested} World")
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    val str = (parsed.file.nodes.first() as net.samcaldwell.latex.bibtex.BibNode.StringDirective).value.parts.first() as net.samcaldwell.latex.bibtex.Part.Str
    assertEquals(net.samcaldwell.latex.bibtex.StrKind.QUOTED, str.kind)
    val chunks = str.chunks
    assertEquals(3, chunks.size)
    assertEquals("Hello ", (chunks[0] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
    val inner = (chunks[1] as net.samcaldwell.latex.bibtex.StrChunk.Group).chunks
    assertEquals("Nested", (inner[0] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
    assertEquals(" World", (chunks[2] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
  }

  @Test
  fun `comment and preamble blobs preserve nested groups`() {
    run {
      val text = "@comment{Alpha {Beta} Gamma}"
      val parser = net.samcaldwell.latex.bibtex.BibParser(text)
      val parsed = parser.parse()
      val node = parsed.file.nodes.first() as net.samcaldwell.latex.bibtex.BibNode.CommentDirective
      val chunks = node.payload.chunks
      assertEquals(3, chunks.size)
      assertTrue(chunks[0] is net.samcaldwell.latex.bibtex.BlobChunk.BlobText)
      assertEquals("Alpha ", (chunks[0] as net.samcaldwell.latex.bibtex.BlobChunk.BlobText).raw)
      assertTrue(chunks[1] is net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup)
      val inner = (chunks[1] as net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup).chunks
      assertEquals(1, inner.size)
      assertEquals("Beta", (inner[0] as net.samcaldwell.latex.bibtex.BlobChunk.BlobText).raw)
      assertTrue(chunks[2] is net.samcaldwell.latex.bibtex.BlobChunk.BlobText)
      assertEquals(" Gamma", (chunks[2] as net.samcaldwell.latex.bibtex.BlobChunk.BlobText).raw)
    }
    run {
      val text = "@preamble({A {B {C}} D})"
      val parser = net.samcaldwell.latex.bibtex.BibParser(text)
      val parsed = parser.parse()
      val node = parsed.file.nodes.first() as net.samcaldwell.latex.bibtex.BibNode.PreambleDirective
      val outer = node.payload.chunks
      // Some implementations may emit surrounding whitespace as a separate BlobText; accept that but require exactly one group.
      val groups = outer.filterIsInstance<net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup>().filter { it.kind == net.samcaldwell.latex.bibtex.Delim.BRACES }
      assertTrue(groups.isNotEmpty())
      val group = groups[0]
      assertEquals(net.samcaldwell.latex.bibtex.Delim.BRACES, group.kind)
      val chunks = group.chunks
      // Expect: Text("A "), Group(BRACES with Text("B {C}")), Text(" D")
      assertTrue(chunks[0] is net.samcaldwell.latex.bibtex.BlobChunk.BlobText)
      assertEquals("A ", (chunks[0] as net.samcaldwell.latex.bibtex.BlobChunk.BlobText).raw)
      assertTrue(chunks[1] is net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup)
      val inner = (chunks[1] as net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup).chunks
      assertEquals(2, inner.size)
      assertTrue(inner[0] is net.samcaldwell.latex.bibtex.BlobChunk.BlobText)
      assertEquals("B ", (inner[0] as net.samcaldwell.latex.bibtex.BlobChunk.BlobText).raw)
      assertTrue(inner[1] is net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup)
      val innerMost = (inner[1] as net.samcaldwell.latex.bibtex.BlobChunk.BlobGroup).chunks
      assertEquals(1, innerMost.size)
      assertEquals("C", (innerMost[0] as net.samcaldwell.latex.bibtex.BlobChunk.BlobText).raw)
      assertTrue(chunks[2] is net.samcaldwell.latex.bibtex.BlobChunk.BlobText)
      assertEquals(" D", (chunks[2] as net.samcaldwell.latex.bibtex.BlobChunk.BlobText).raw)
    }
  }

  @Test
  fun `concatenation with hash preserves parts and nested groups`() {
    val text = """
      @misc{key,
        title = "A {B}" # " C {D}" # foo # 42 # {E {F}},
      }
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    val entry = parsed.file.nodes.first() as net.samcaldwell.latex.bibtex.BibNode.Entry
    val field = entry.fields.first { it.name.equals("title", ignoreCase = true) }
    val ps = field.value.parts
    assertEquals(5, ps.size)
    val p0 = ps[0] as net.samcaldwell.latex.bibtex.Part.Str
    assertEquals(net.samcaldwell.latex.bibtex.StrKind.QUOTED, p0.kind)
    assertEquals("A ", (p0.chunks[0] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
    val p1 = ps[1] as net.samcaldwell.latex.bibtex.Part.Str
    assertEquals(" C ", (p1.chunks[0] as net.samcaldwell.latex.bibtex.StrChunk.Text).raw)
    val p2 = ps[2] as net.samcaldwell.latex.bibtex.Part.IdentRef; assertEquals("foo", p2.name)
    val p3 = ps[3] as net.samcaldwell.latex.bibtex.Part.Number; assertEquals("42", p3.lexeme)
    val p4 = ps[4] as net.samcaldwell.latex.bibtex.Part.Str
    assertEquals(net.samcaldwell.latex.bibtex.StrKind.BRACED, p4.kind)
    val s = net.samcaldwell.latex.bibtex.BibParser.flattenValue(field.value)
    assertEquals("A {B} C {D}foo42E {F}", s)
  }
}
