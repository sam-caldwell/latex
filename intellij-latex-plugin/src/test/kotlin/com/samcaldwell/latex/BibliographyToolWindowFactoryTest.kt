package com.samcaldwell.latex

import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import io.mockk.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class BibliographyToolWindowFactoryTest : BasePlatformTestCase() {

  override fun tearDown() {
    try { unmockkAll() } finally { super.tearDown() }
  }

  @Test
  fun testToolWindowCreatesContentAndRefreshesList() {
    // Prepare a temp library file via a spy service replacement
    val tmp = Files.createTempDirectory("bibtest")
    val libPath = tmp.resolve("library.bib")
    Files.write(libPath, "% test library\n\n".toByteArray(StandardCharsets.UTF_8))

    val realSvc = BibLibraryService(project)
    val svc = spyk(realSvc)
    every { svc.libraryPath() } returns libPath
    every { svc.ensureLibraryExists() } answers {
      if (!Files.exists(libPath)) Files.write(libPath, "% header\n\n".toByteArray())
      libPath
    }
    ServiceContainerUtil.replaceService(project, BibLibraryService::class.java, svc, testRootDisposable)

    // Upsert one entry to be shown
    val entry = BibLibraryService.BibEntry("misc", "tw-key", mapOf("title" to "ToolWindow Entry", "source" to "manual entry", "verified" to "false"))
    assertTrue(project.getService(BibLibraryService::class.java).upsertEntry(entry))

    // Mock ToolWindow and ContentManager to capture added content
    val added = mutableListOf<Content>()
    val cm = mockk<ContentManager>(relaxed = true)
    every { cm.addContent(capture(added)) } answers { }
    val tw = mockk<ToolWindow>(relaxed = true)
    every { tw.contentManager } returns cm

    // Create content via factory
    val factory = BibliographyToolWindowFactory()
    factory.createToolWindowContent(project, tw)

    // Content added
    assertEquals(1, added.size)
    val content = added[0]
    assertNotNull(content.component)

    // Find and click the Refresh button to populate the JList
    val panel = content.component
    val buttons = panel.componentsDepthFirst().filterIsInstance<javax.swing.JButton>()
    val refresh = buttons.firstOrNull { it.text == "Refresh" }
    assertNotNull(refresh)
    refresh!!.doClick()

    // Find the list and ensure it contains our entry
    val lists = panel.componentsDepthFirst().filterIsInstance<javax.swing.JList<*>>()
    assertTrue(lists.isNotEmpty())
    val list = lists.first()
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

