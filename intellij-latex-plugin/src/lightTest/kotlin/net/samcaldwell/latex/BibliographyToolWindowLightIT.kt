package net.samcaldwell.latex

import com.intellij.openapi.vfs.LocalFileSystem
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

class BibliographyToolWindowLightIT : LightPlatformTestCase() {
  fun testToolWindowSmoke_refreshAndListEntries() {
    // Point BibLibraryService at a temp file instead of project.basePath
    val temp = Files.createTempDirectory("bib-it")
    val lib = temp.resolve("library.bib")
    Files.write(lib, "% header\n\n".toByteArray(StandardCharsets.UTF_8))

    val real = BibLibraryService(project)
    val svc = io.mockk.spyk(real)
    every { svc.libraryPath() } returns lib
    every { svc.ensureLibraryExists() } answers {
      if (!Files.exists(lib)) Files.write(lib, "% header\n\n".toByteArray())
      lib
    }
    project.replaceService(BibLibraryService::class.java, svc, testRootDisposable)

    // Upsert one entry to be shown
    val entry = BibLibraryService.BibEntry("misc", "tw-key", mapOf("title" to "ToolWindow Entry", "source" to "manual entry", "verified" to "false"))
    assertTrue(project.getService(BibLibraryService::class.java).upsertEntry(entry))

    // Mock ToolWindow and capture added content
    val added = slot<Content>()
    val cm = mockk<ContentManager>(relaxed = true)
    every { cm.addContent(capture(added)) } answers { }
    val tw = mockk<ToolWindow>(relaxed = true)
    every { tw.contentManager } returns cm

    // Create the content via factory
    BibliographyToolWindowFactory().createToolWindowContent(project, tw)

    // Ensure content was added
    assertTrue(added.isCaptured)
    val component = added.captured.component
    assertNotNull(component)

    // Click the Refresh button
    val refresh = component.componentsDepthFirst().filterIsInstance<javax.swing.JButton>().firstOrNull { it.text == "Refresh" }
    assertNotNull(refresh)
    refresh!!.doClick()

    // Find the bibliography entry list (not the authors list)
    val list = component.componentsDepthFirst()
      .filterIsInstance<javax.swing.JList<*>>()
      .firstOrNull { jl ->
        (0 until jl.model.size).any { idx -> jl.model.getElementAt(idx) is BibLibraryService.BibEntry }
      }
    assertNotNull(list)
    list!!
    val hasKey = (0 until list.model.size).any { idx ->
      val e = list.model.getElementAt(idx)
      e is BibLibraryService.BibEntry && e.key == "tw-key"
    }
    assertTrue(hasKey)
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
