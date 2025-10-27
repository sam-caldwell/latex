package net.samcaldwell.latex

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UrlRfc3986ValidationTest {
  private fun svc(): BibLibraryService = BibLibraryService(mockk(relaxed = true))

  @Test
  fun `accept known-good RFC3986 URLs`() {
    val urls = listOf(
      "https://www.pbs.org/wgbh/americanexperience/features/carter-crisis/",
      "https://math-atlas.sourceforge.net/devel/assembly/mipsabi32.pdf",
      "https://arxiv.org/html/2404.11420v1",
      "https://doi.org/10.1038/s41566-025-01648-7?query=mock&query2=fake"
    )
    val s = svc()
    for (u in urls) {
      assertTrue(s.isValidHttpUrlRfc3986(u), "Expected valid URL: $u")
    }
  }
}

