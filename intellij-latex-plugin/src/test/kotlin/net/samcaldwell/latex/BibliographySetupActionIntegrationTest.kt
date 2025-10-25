package net.samcaldwell.latex

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import io.mockk.*
import com.intellij.openapi.command.WriteCommandAction
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BibliographySetupActionIntegrationTest {

  @After
  fun cleanup() { unmockkAll() }

  @Test
  fun testActionInsertsPreambleAndPrint() {
    // Prepare a simple backing document backed by StringBuilder
    val initial = "% test\n\\begin{document}\nHello\n\\end{document}\n"
    val buf = StringBuilder(initial)
    val doc = mockk<Document>(relaxed = true)
    every { doc.charsSequence } answers { buf.toString() }
    every { doc.insertString(any(), any<String>()) } answers {
      val off = firstArg<Int>()
      val s = secondArg<String>()
      buf.insert(off, s)
      Unit
    }
    val editor = mockk<Editor>(relaxed = true)
    every { editor.document } returns doc

    // Mock project and service lookup to avoid touching real Application
    val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
    every { project.basePath } returns null
    every { project.getService(BibLibraryService::class.java) } returns BibLibraryService(project)

    // Stub modal dialogs and write command wrapper
    mockkStatic(Messages::class)
    every { Messages.showDialog(project, any(), any(), any<Array<String>>(), any(), any()) } returns 0
    every { Messages.showInfoMessage(project, any(), any()) } returns Unit

    mockkStatic(WriteCommandAction::class)
    every { WriteCommandAction.runWriteCommandAction(project, any<Runnable>()) } answers {
      secondArg<Runnable>().run()
    }

    // Build an AnActionEvent with required data
    val vfile = mockk<com.intellij.openapi.vfs.VirtualFile>(relaxed = true)
    every { vfile.extension } returns TexFileType.defaultExtension
    val dataContext = DataContext { key ->
      when (key) {
        CommonDataKeys.PROJECT.name -> project
        CommonDataKeys.EDITOR.name -> editor
        CommonDataKeys.VIRTUAL_FILE.name -> vfile
        else -> null
      }
    }
    val action = BibliographySetupAction()
    val fakeManager = mockk<ActionManager>(relaxed = true)
    val event = AnActionEvent(null, dataContext, ActionPlaces.UNKNOWN, action.templatePresentation.clone(), fakeManager, 0)

    // Precondition
    action.update(event)
    assertTrue(event.presentation.isEnabledAndVisible)

    // Execute and verify (inspect backing buffer)
    action.actionPerformed(event)
    val result = buf.toString()
    assertTrue(result.contains("\\usepackage"))
    // Should reference a bibliography file ending with library.bib (absolute or relative)
    assertTrue(result.contains("library.bib}"))
    assertTrue(result.contains("\\printbibliography"))
  }
}
