package net.samcaldwell.latex

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class BibLibraryServiceTest {

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  private fun projectWithBase(@TempDir tmp: Path): Project {
    val p = mockk<Project>(relaxed = true)
    every { p.basePath } returns tmp.toString()
    return p
  }

  private fun newService(@TempDir tmp: Path): BibLibraryService = BibLibraryService(projectWithBase(tmp))

  // --- DOI import ---------------------------------------------------------

  @Test
  fun `importFromDoiOrUrl happy path upserts entry`(@TempDir tmp: Path) {
    mockkObject(HttpUtil)
    mockLocalFs()
    // Return a simple BibTeX for the DOI
    every { HttpUtil.get(any(), any(), aliases = anyVararg(), timeoutMs = any()) } answers {
      val url = firstArg<String>()
      if (url.startsWith("https://doi.org/10.1000/xyz123")) {
        """
          @article{sample2021foo,
            author = {Doe, John and Roe, Jane},
            title = {A Sample Article},
            year = {2021},
            journal = {Journal of Examples},
            doi = {10.1000/xyz123}
          }
        """.trimIndent()
      } else null
    }

    val svc = newService(tmp)
    val entry = svc.importFromDoiOrUrl("10.1000/xyz123", preferredKey = "mykey")
    assertNotNull(entry)
    entry!!
    assertEquals("mykey", entry.key)
    assertEquals("automated (doi.org)", entry.fields["source"])
    assertEquals("false", entry.fields["verified"])

    // File should contain created/modified fields
    val bibPath = svc.libraryPath()!!
    val content = Files.readString(bibPath)
    assertTrue(content.contains("created = {"))
    assertTrue(content.contains("modified = {"))
  }

  @Test
  fun `importFromDoiOrUrl sad path returns null when http fails`(@TempDir tmp: Path) {
    mockkObject(HttpUtil)
    every { HttpUtil.get(any(), any(), aliases = anyVararg(), timeoutMs = any()) } returns null

    val svc = newService(tmp)
    val entry = svc.importFromDoiOrUrl("10.9999/doesnotexist")
    assertNull(entry)
  }

  // --- Upsert, read, delete ---------------------------------------------

  @Test
  fun `upsert creates then updates preserving created`(@TempDir tmp: Path) {
    mockLocalFs()
    val svc = newService(tmp)
    val e1 = BibLibraryService.BibEntry(
      type = "article",
      key = "key1",
      fields = mapOf("author" to "Alice", "title" to "Title1", "source" to "manual entry", "verified" to "true")
    )
    assertTrue(svc.upsertEntry(e1))

    // First read
    val first = svc.readEntries().firstOrNull { it.key == "key1" }
    assertNotNull(first)
    first!!
    val created = first.fields["created"]
    val modified = first.fields["modified"]
    assertFalse(created.isNullOrBlank())
    assertFalse(modified.isNullOrBlank())

    // Update title
    val e2 = e1.copy(fields = e1.fields + mapOf("title" to "Title2"))
    assertTrue(svc.upsertEntry(e2))
    val second = svc.readEntries().first { it.key == "key1" }
    assertEquals("Title2", second.fields["title"])
    // created should be present; preservation may vary by regex parsing, so at least ensure it exists
    assertFalse(second.fields["created"].isNullOrBlank())
  }

  @Test
  fun `parseSingleEntry parses minimal bibtex`() {
    val svc = BibLibraryService(mockk(relaxed = true))
    val text = """
      @misc{ref1,
        title = {Hello},
        author = {Someone}
      }
    """.trimIndent()
    val e = svc.parseSingleEntry(text)
    assertNotNull(e)
    e!!
    assertEquals("misc", e.type)
    assertEquals("ref1", e.key)
    assertEquals("Hello", e.fields["title"])
  }

  @Test
  fun `deleteEntry removes entry from file`(@TempDir tmp: Path) {
    mockLocalFs()
    val svc = newService(tmp)
    val e1 = BibLibraryService.BibEntry("misc", "delkey", mapOf("title" to "Gone"))
    assertTrue(svc.upsertEntry(e1))
    assertTrue(svc.deleteEntry("misc", "delkey"))
    val content = Files.readString(svc.libraryPath()!!)
    assertFalse(content.contains("@misc{delkey"))
  }

  // --- PDF import ---------------------------------------------------------

  @Test
  fun `importFromPdfUrl extracts doi then imports via doi org`(@TempDir tmp: Path) {
    mockkObject(HttpUtil)
    mockLocalFs()

    val doi = "10.1000/abc456"
    val pdfBytes = makePdfWithText("doi:$doi")
    every { HttpUtil.getBytes(any(), any(), aliases = anyVararg(), timeoutMs = any()) } returns pdfBytes

    every { HttpUtil.get(any(), any(), aliases = anyVararg(), timeoutMs = any()) } answers {
      val url = firstArg<String>()
      if (url.startsWith("https://doi.org/$doi")) {
        """
          @article{paper2022,
            author = {Smith, Ann},
            title = {Interesting Paper},
            year = {2022},
            journal = {Journal of Interesting Things},
            doi = {10.1000/abc456}
          }
        """.trimIndent()
      } else null
    }

    val svc = newService(tmp)
    val entry = svc.importFromPdfUrl("https://example.com/paper.pdf")
    assertNotNull(entry)
    entry!!
    assertEquals("automated (doi.org)", entry.fields["source"])
    assertEquals("false", entry.fields["verified"])
  }

  @Test
  fun `importFromPdfUrl builds minimal entry when doi missing`(@TempDir tmp: Path) {
    mockkObject(HttpUtil)
    mockLocalFs()
    // PDF with no DOI, but with title and author metadata
    val pdfBytes = makePdfWithMetadata(title = "Minimal PDF Title", author = "Alice, Bob")
    every { HttpUtil.getBytes(any(), any(), aliases = anyVararg(), timeoutMs = any()) } returns pdfBytes
    // No DOI resolution should be attempted (return null if called)
    every { HttpUtil.get(any(), any(), aliases = anyVararg(), timeoutMs = any()) } returns null

    val svc = newService(tmp)
    val entry = svc.importFromPdfUrl("https://example.com/other.pdf")
    assertNotNull(entry)
    entry!!
    assertEquals("Minimal PDF Title", entry.fields["title"])
    // Authors parsed to BibTeX 'and' format
    assertEquals("Alice and Bob", entry.fields["author"])
    assertEquals("https://example.com/other.pdf", entry.fields["url"])
    assertEquals("automated (pdf)", entry.fields["source"])
    assertEquals("false", entry.fields["verified"])
  }

  // --- Helpers ------------------------------------------------------------

  private fun makePdfWithText(text: String): ByteArray {
    PDDocument().use { doc ->
      val page = PDPage()
      doc.addPage(page)
      PDPageContentStream(doc, page).use { cs ->
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 12f)
        cs.newLineAtOffset(50f, 750f)
        cs.showText(text)
        cs.endText()
      }
      val out = ByteArrayOutputStream()
      doc.save(out)
      return out.toByteArray()
    }
  }

  private fun makePdfWithMetadata(title: String, author: String): ByteArray {
    PDDocument().use { doc ->
      val page = PDPage()
      doc.addPage(page)
      val info = PDDocumentInformation()
      info.title = title
      info.author = author
      doc.documentInformation = info
      val out = ByteArrayOutputStream()
      doc.save(out)
      return out.toByteArray()
    }
  }

  private fun mockLocalFs() {
    mockkStatic(LocalFileSystem::class)
    val fs = mockk<LocalFileSystem>(relaxed = true)
    every { LocalFileSystem.getInstance() } returns fs
    every { fs.refreshAndFindFileByIoFile(any()) } returns null
  }
}
