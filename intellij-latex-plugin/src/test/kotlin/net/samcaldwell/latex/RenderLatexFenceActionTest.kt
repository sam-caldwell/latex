package net.samcaldwell.latex

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RenderLatexFenceActionTest {
  private fun makeDoc(text: String): Document {
    val buf = StringBuilder(text)
    return mockk<Document>(relaxed = true).also { doc ->
      every { doc.charsSequence } answers { buf.toString() }
    }
  }

  @Test
  fun `update enables only inside latex fence in markdown`() {
    val md = """
      # Title

      ```latex
      E = mc^2
      ```
    """.trimIndent()
    val doc = makeDoc(md)
    val editor = mockk<Editor>(relaxed = true)
    every { editor.document } returns doc
    every { editor.caretModel.currentCaret.offset } returns md.indexOf("E = mc")

    val vfile = mockk<com.intellij.openapi.vfs.VirtualFile>(relaxed = true)
    every { vfile.name } returns "readme.md"

    val dataContext = DataContext { key ->
      when (key) {
        CommonDataKeys.EDITOR.name -> editor
        CommonDataKeys.VIRTUAL_FILE.name -> vfile
        else -> null
      }
    }
    val action = RenderLatexFenceAction()
    val event = AnActionEvent(null, dataContext, ActionPlaces.UNKNOWN, action.templatePresentation.clone(), mockk(relaxed = true), 0)

    action.update(event)
    assertTrue(event.presentation.isEnabledAndVisible)

    // Move caret outside fence
    every { editor.caretModel.currentCaret.offset } returns md.indexOf("# Title")
    action.update(event)
    assertFalse(event.presentation.isEnabledAndVisible)
  }
}
