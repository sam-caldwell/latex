package net.samcaldwell.latex

import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JComponent

class BibliographyToolWindowFactoryIntegrationTest {

  @After
  fun cleanup() { unmockkAll() }

  @Test
  fun testFactoryAddsContent() {
    // Mock project with a real service instance to allow form initialization
    val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
    every { project.getService(BibLibraryService::class.java) } returns BibLibraryService(project)
    every { project.messageBus } returns mockk(relaxed = true)

    // Mock ContentFactory static and ToolWindow/ContentManager
    val contentFactory = mockk<ContentFactory>(relaxed = true)
    mockkStatic(ContentFactory::class)
    every { ContentFactory.getInstance() } returns contentFactory

    val captured = mutableListOf<Content>()
    val content = mockk<Content>(relaxed = true)
    // Ensure content.component returns the component passed to createContent
    every { content.component } returns mockk<JComponent>(relaxed = true)
    every { contentFactory.createContent(any(), any(), any()) } answers {
      val comp = firstArg<JComponent>()
      every { content.component } returns comp
      content
    }

    val cm = mockk<com.intellij.ui.content.ContentManager>(relaxed = true)
    every { cm.addContent(capture(captured)) } answers { }
    val tw = mockk<ToolWindow>(relaxed = true)
    every { tw.contentManager } returns cm

    // Invoke factory
    BibliographyToolWindowFactory().createToolWindowContent(project, tw)

    // Verify one content was added and it wraps a component
    assertEquals(1, captured.size)
    assertNotNull(captured[0].component)
  }
}
