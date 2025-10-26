package net.samcaldwell.latex

import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class BibliographyVerifyLightIT : LightPlatformTestCase() {
  fun testVerify_noParserErrors_forWellFormedEntry() {
    // Point BibLibraryService at a temp file instead of project.basePath
    val temp = Files.createTempDirectory("bib-it-verify")
    val lib = temp.resolve("library.bib")
    val text = """
      @online{neal-davis-recidivism,
        author = {Neal Davis Law Firm},
        abstract = {Learn the truth behind why criminals reoffend and what steps can be taken to support their successful reintegration into society.},
        created = {2025-10-24T01:10:31Z},
        date = {2025-10-24},
        journal = {Neal Davis Law Firm},
        keywords = {article},
        modified = {2025-10-24T01:53:22Z},
        source = {web},
        title = {Breaking the Cycle of Recidivism: Understanding Causes & Solutions},
        type = {article},
        url = {https://www.nealdavislaw.com/recidivism-causes-and-solutions/},
        year = {n.d.},
      }
    """.trimIndent()
    Files.write(lib, text.toByteArray(StandardCharsets.UTF_8))

    val real = BibLibraryService(project)
    val svc = io.mockk.spyk(real)
    every { svc.libraryPath() } returns lib
    every { svc.ensureLibraryExists() } answers {
      if (!Files.exists(lib)) Files.write(lib, "% header\n\n".toByteArray())
      lib
    }
    project.replaceService(BibLibraryService::class.java, svc, testRootDisposable)

    // Mock ToolWindow and capture added content
    val added = slot<Content>()
    val cm = mockk<ContentManager>(relaxed = true)
    every { cm.addContent(capture(added)) } answers { }
    val tw = mockk<ToolWindow>(relaxed = true)
    every { tw.contentManager } returns cm

    // Create content
    BibliographyToolWindowFactory().createToolWindowContent(project, tw)
    assertTrue(added.isCaptured)
    val component = added.captured.component

    // Click Verify button (tooltip "Verify .bib file")
    val verifyBtn = component.componentsDepthFirst().filterIsInstance<javax.swing.JButton>()
      .firstOrNull { it.toolTipText == "Verify .bib file" }
    assertNotNull(verifyBtn)
    verifyBtn!!.doClick()

    // Find the summary label and assert Errors: 0
    val summary = component.componentsDepthFirst().filterIsInstance<javax.swing.JLabel>()
      .firstOrNull { it.text != null && it.text.contains("Errors:") }
    assertNotNull(summary)
    assertTrue("Expected no parser errors, got: ${summary!!.text}", summary.text.contains("Errors: 0"))
  }
}

private fun java.awt.Component.componentsDepthFirst(): Sequence<java.awt.Component> = sequence {
  yield(this@componentsDepthFirst)
  if (this@componentsDepthFirst is java.awt.Container) {
    for (child in this@componentsDepthFirst.components) {
      yieldAll(child.componentsDepthFirst())
    }
  }
}

