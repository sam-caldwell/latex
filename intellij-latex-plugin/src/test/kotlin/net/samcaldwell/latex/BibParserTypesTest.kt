package net.samcaldwell.latex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BibParserTypesTest {
  @Test
  fun `parser accepts inproceedings`() {
    val text = """
      @inproceedings{smith2020,
        title = {Proceedings Paper},
      }
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    assertTrue(parsed.errors.isEmpty(), "Unexpected parser errors: ${parsed.errors}")
    val n = parsed.file.nodes.firstOrNull() as? net.samcaldwell.latex.bibtex.BibNode.Entry
    assertNotNull(n)
    assertEquals("inproceedings", n!!.type.lowercase())
  }

  @Test
  fun `parser accepts proceedings`() {
    val text = """
      @proceedings{conf2020,
        title = {The Conference Proceedings},
      }
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    assertTrue(parsed.errors.isEmpty(), "Unexpected parser errors: ${parsed.errors}")
    val n = parsed.file.nodes.firstOrNull() as? net.samcaldwell.latex.bibtex.BibNode.Entry
    assertNotNull(n)
    assertEquals("proceedings", n!!.type.lowercase())
  }
}

