package com.samcaldwell.latex

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.Test

class BibliographySetupActionTest : BasePlatformTestCase() {

  override fun tearDown() {
    try { unmockkAll() } finally { super.tearDown() }
  }

  @Test
  fun testBibliographySetupInsertsPreambleAndPrint() {
    // Prepare a minimal TeX file
    val initial = """
      % Test doc
      \n
      \begin{document}
      Hello World
      \end{document}
    """.trimIndent()
    val psiFile = myFixture.configureByText("main.tex", initial)

    // Stub modal dialogs
    mockkStatic(Messages::class)
    every { Messages.showDialog(project, any(), any(), any<Array<String>>(), any(), any()) } returns 0 // choose APA7
    every { Messages.showInfoMessage(project, any(), any()) } returns Unit

    val actionId = "com.samcaldwell.latex.BibliographySetupAction"
    val action = ActionManager.getInstance().getAction(actionId)
    assertNotNull(action)

    val presentation = action.templatePresentation.clone()
    val dataContext = SimpleDataContext.builder()
      .add(CommonDataKeys.PROJECT, project)
      .add(CommonDataKeys.EDITOR, myFixture.editor)
      .add(CommonDataKeys.VIRTUAL_FILE, psiFile.virtualFile)
      .build()
    val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dataContext)

    // Ensure visible on .tex
    action.update(event)
    assertTrue(event.presentation.isEnabledAndVisible)

    // Execute
    action.actionPerformed(event)

    // Verify content changes
    val text = myFixture.editor.document.text
    assertTrue(text.contains("\\usepackage[style=apa,backend=biber]{biblatex}"))
    assertTrue(text.contains("\\addbibresource{library.bib}"))
    assertTrue(text.contains("\\printbibliography"))
  }
}

