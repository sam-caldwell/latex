package com.samcaldwell.latex

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FileTypesAndLanguagesTest {
  @Test
  fun `tex file type basics`() {
    assertEquals("LaTeX", TexFileType.name)
    assertEquals("tex", TexFileType.defaultExtension)
    assertNotNull(TexFileType.icon)
  }

  @Test
  fun `bib file type basics`() {
    assertEquals("BibTeX", BibFileType.name)
    assertEquals("bib", BibFileType.defaultExtension)
    assertNotNull(BibFileType.icon)
  }

  @Test
  fun `languages have expected IDs`() {
    assertEquals("LaTeX", TexLanguage.id)
    assertEquals("BibTeX", BibLanguage.id)
  }
}

