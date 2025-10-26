package net.samcaldwell.latex

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BibParserVerifyTest {
  @Test
  fun `parser accepts standard braced fields without bogus equals error`() {
    val text = """
      @online{neal-davis-recidivism,
        author = {Neal Davis Law Firm},
        abstract = {Learn the truth behind why criminals reoffend and what steps can be taken to support their successful reintegration into society.},
        created = {2025-10-24T01:10:31Z},
        date = {2025-10-24},
        journaltitle = {Neal Davis Law Firm},
        keywords = {article},
        modified = {2025-10-24T01:53:22Z},
        source = {web},
        title = {Breaking the Cycle of Recidivism: Understanding Causes & Solutions},
        howpublished = {Televised speech},
        type = {article},
        url = {https://www.nealdavislaw.com/recidivism-causes-and-solutions/},
        year = {n.d.},
      }
    """.trimIndent()
    val parser = net.samcaldwell.latex.bibtex.BibParser(text)
    val parsed = parser.parse()
    val noMissingEq = parsed.errors.none { it.message.contains("Missing '=' after field name") }
    assertTrue(noMissingEq, "Parser erroneously reported missing '=' after field name: ${parsed.errors}")
  }
}
