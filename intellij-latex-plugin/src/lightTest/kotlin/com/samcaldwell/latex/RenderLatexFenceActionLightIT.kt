package com.samcaldwell.latex

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightVirtualFile

class RenderLatexFenceActionLightIT : LightPlatformTestCase() {
  fun testUpdateEnabledInsideLatexFence() {
    val md = """
      # T

      ```latex
      E = mc^2
      ```
    """.trimIndent()

    val document = EditorFactory.getInstance().createDocument(md)
    val editor = EditorFactory.getInstance().createEditor(document, project)
    try {
      val vf = LightVirtualFile("a.md", md)
      val action = RenderLatexFenceAction()
      val dataContext = DataContext { key ->
        when (key) {
          CommonDataKeys.EDITOR.name -> editor
          CommonDataKeys.VIRTUAL_FILE.name -> vf
          else -> null
        }
      }
      val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dataContext)

      val off = md.indexOf("E = mc")
      editor.caretModel.moveToOffset(off)
      action.update(event)
      assertTrue(event.presentation.isEnabledAndVisible)

      editor.caretModel.moveToOffset(md.indexOf("# T"))
      action.update(event)
      assertFalse(event.presentation.isEnabledAndVisible)
    } finally {
      EditorFactory.getInstance().releaseEditor(editor)
    }
  }
}

