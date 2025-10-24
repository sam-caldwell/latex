package net.samcaldwell.latex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.swing.*

class TexPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor, Disposable {
  private val panel = JPanel(BorderLayout())
  private val toolbar = JToolBar()
  private val preview = PdfPreviewPanel()
  private val executor = ScheduledThreadPoolExecutor(1)
  private var scheduled: ScheduledFuture<*>? = null
  private var lastCompiledPdf: Path? = null
  private val changeSupport = PropertyChangeSupport(this)

  private val document: Document = FileDocumentManager.getInstance().getDocument(file)!!

  init {
    toolbar.isFloatable = false
    val refreshBtn = JButton("Refresh")
    val zoomInBtn = JButton("+")
    val zoomOutBtn = JButton("-")
    toolbar.add(refreshBtn)
    toolbar.addSeparator()
    toolbar.add(zoomOutBtn)
    toolbar.add(zoomInBtn)

    refreshBtn.addActionListener { scheduleCompile(immediate = true) }
    zoomInBtn.addActionListener { preview.zoomIn(); preview.revalidate() }
    zoomOutBtn.addActionListener { preview.zoomOut(); preview.revalidate() }

    panel.add(toolbar, BorderLayout.NORTH)
    panel.add(JScrollPane(preview), BorderLayout.CENTER)

    document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        scheduleCompile(immediate = false)
      }
    }, this)

    // Initial compile
    scheduleCompile(immediate = true)
  }

  private fun scheduleCompile(immediate: Boolean) {
    scheduled?.cancel(false)
    val delay = if (immediate) 0L else 800L
    scheduled = executor.schedule({ compileNow() }, delay, TimeUnit.MILLISECONDS)
  }

  private fun compileNow() {
    val service = project.getService(LatexCompilerService::class.java)
    val pdf = service.compile(file)
    if (pdf != null && pdf != lastCompiledPdf) {
      lastCompiledPdf = pdf
      ApplicationManager.getApplication().invokeLater {
        preview.loadPdf(pdf)
      }
      VfsUtil.markDirtyAndRefresh(false, false, false, VfsUtil.findFileByIoFile(pdf.toFile(), true))
    }
  }

  override fun getComponent(): JComponent = panel
  override fun getPreferredFocusedComponent(): JComponent? = null
  override fun getName(): String = "Preview"
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = file.isValid
  override fun getCurrentLocation(): FileEditorLocation? = null

  override fun addPropertyChangeListener(listener: PropertyChangeListener) {
    changeSupport.addPropertyChangeListener(listener)
  }

  override fun removePropertyChangeListener(listener: PropertyChangeListener) {
    changeSupport.removePropertyChangeListener(listener)
  }

  override fun dispose() {
    try { executor.shutdownNow() } catch (_: Throwable) {}
  }
}
