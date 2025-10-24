package net.samcaldwell.latex

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.replaceService
import io.mockk.*
import java.nio.file.Files

class RenderLatexFenceActionEndToEndLightIT : LightPlatformTestCase() {
  fun testActionPerformsCompileWithoutShowingUi() {
    val md = """
      # T

      ```latex
      E = mc^2
      ```
    """.trimIndent()
    val vf = com.intellij.testFramework.LightVirtualFile("note.md", md)
    val editor = com.intellij.openapi.editor.EditorFactory.getInstance().createEditor(
      com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(md), project
    )

    try {
      editor.caretModel.moveToOffset(md.indexOf("E = mc"))

      mockkStatic(LocalFileSystem::class)
      val lfs = mockk<LocalFileSystem>(relaxed = true)
      every { LocalFileSystem.getInstance() } returns lfs
      every { lfs.refreshAndFindFileByIoFile(any()) } answers { mockk<VirtualFile>(relaxed = true) }

      val mockService = mockk<LatexCompilerService>(relaxed = true)
      val fakePdf = Files.createTempFile("snippet", ".pdf")
      every { mockService.compile(any()) } returns fakePdf
      project.replaceService(LatexCompilerService::class.java, mockService, testRootDisposable)

      mockkStatic(ApplicationManager::class)
      val app = mockk<com.intellij.openapi.application.Application>(relaxed = true)
      every { ApplicationManager.getApplication() } returns app
      every { app.invokeLater(any()) } answers { /* swallow UI */ }

      val action = RenderLatexFenceAction()
      val dataContext = DataContext { key ->
        when (key) {
          CommonDataKeys.EDITOR.name -> editor
          CommonDataKeys.VIRTUAL_FILE.name -> vf
          CommonDataKeys.PROJECT.name -> project
          else -> null
        }
      }
      val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dataContext)
      action.actionPerformed(event)

      verify(exactly = 1) { mockService.compile(any()) }
    } finally {
      com.intellij.openapi.editor.EditorFactory.getInstance().releaseEditor(editor)
      unmockkAll()
    }
  }
}
