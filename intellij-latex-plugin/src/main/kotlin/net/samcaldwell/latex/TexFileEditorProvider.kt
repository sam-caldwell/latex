package net.samcaldwell.latex

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.UserDataHolderBase
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent
import javax.swing.JSplitPane

class TexFileEditorProvider : FileEditorProvider {
  override fun accept(project: Project, file: VirtualFile): Boolean {
    return file.extension?.lowercase() == TexFileType.defaultExtension
  }

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
    val previewEditor = TexPreviewEditor(project, file)
    return TexSplitEditor(textEditor, previewEditor)
  }

  override fun getEditorTypeId(): String = "latex-split-editor"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class TexSplitEditor(
  private val textEditor: TextEditor,
  private val previewEditor: TexPreviewEditor
) : UserDataHolderBase(), FileEditor {

  private val component: JSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
    leftComponent = textEditor.component
    rightComponent = previewEditor.component
    resizeWeight = 0.5
    // Use proportional divider location method explicitly (double)
    setDividerLocation(0.5)
  }

  private val changeSupport = PropertyChangeSupport(this)

  override fun getComponent(): JComponent = component
  override fun getPreferredFocusedComponent(): JComponent? = textEditor.preferredFocusedComponent
  override fun getName(): String = "LaTeX Editor + Preview"
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = textEditor.isModified
  override fun isValid(): Boolean = textEditor.isValid && previewEditor.isValid

  override fun addPropertyChangeListener(listener: PropertyChangeListener) {
    changeSupport.addPropertyChangeListener(listener)
  }

  override fun removePropertyChangeListener(listener: PropertyChangeListener) {
    changeSupport.removePropertyChangeListener(listener)
  }

  override fun dispose() {
    textEditor.dispose()
    previewEditor.dispose()
  }
}
