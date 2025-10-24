package com.samcaldwell.latex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Paths
import javax.swing.*

class BibliographyToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = BibliographyForm(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}

class BibliographyForm(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private val listModel = DefaultListModel<BibLibraryService.BibEntry>()
  private val entryList = JList(listModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        if (value is BibLibraryService.BibEntry) {
          val title = value.fields["title"]?.takeIf { it.isNotBlank() }
          val year = value.fields["year"]?.takeIf { it.isNotBlank() }
          val parts = mutableListOf<String>()
          parts.add(value.key)
          if (title != null) parts.add("— $title")
          if (year != null) parts.add("($year)")
          parts.add("[${value.type}]")
          c.text = parts.joinToString(" ")
        }
        return c
      }
    }
    addListSelectionListener {
      if (!it.valueIsAdjusting) {
        val e = selectedValue
        if (e != null) loadEntryIntoForm(e)
      }
    }
  }
  private val typeField = JComboBox(arrayOf("article", "book", "inproceedings", "misc"))
  private val keyField = JTextField()
  private val authorField = JTextField()
  private val titleField = JTextField()
  private val yearField = JTextField()
  private val journalField = JTextField()
  private val booktitleField = JTextField()
  private val publisherField = JTextField()
  private val doiField = JTextField()
  private val urlField = JTextField()
  private val importField = JTextField()

  init {
    val form = JPanel(GridBagLayout())
    var row = 0
    fun addRow(label: String, comp: JComponent) {
      val lc = GridBagConstraints().apply {
        gridx = 0; gridy = row; weightx = 0.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      val fc = GridBagConstraints().apply {
        gridx = 1; gridy = row; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      form.add(JLabel(label), lc)
      form.add(comp, fc)
      row++
    }

    addRow("Type", typeField)
    addRow("Key", keyField)
    addRow("Author", authorField)
    addRow("Title", titleField)
    addRow("Year", yearField)
    addRow("Journal", journalField)
    addRow("Booktitle", booktitleField)
    addRow("Publisher", publisherField)
    addRow("DOI", doiField)
    addRow("URL", urlField)

    val buttonPanel = JPanel()
    val saveButton = JButton("Save Entry")
    saveButton.addActionListener { saveEntry() }
    val openButton = JButton("Open library.bib")
    openButton.addActionListener { openLibrary() }
    val importButton = JButton("Import DOI/URL/Title → BibTeX")
    importButton.addActionListener { importByDoiOrUrl() }
    val aiLookupButton = JButton("Lookup (AI)")
    aiLookupButton.toolTipText = "Use JetBrains AI Assistant if installed"
    aiLookupButton.addActionListener { importViaAi() }
    buttonPanel.add(saveButton)
    buttonPanel.add(openButton)
    buttonPanel.add(JLabel(" DOI/URL:"))
    buttonPanel.add(importField.apply { columns = 18 })
    buttonPanel.add(importButton)
    buttonPanel.add(aiLookupButton)

    val rightPanel = JPanel(BorderLayout())
    rightPanel.add(JScrollPane(form), BorderLayout.CENTER)
    rightPanel.add(buttonPanel, BorderLayout.SOUTH)

    val leftPanel = JPanel(BorderLayout())
    val refresh = JButton("Refresh")
    refresh.addActionListener { refreshList() }
    val duplicateBtn = JButton("Duplicate")
    duplicateBtn.addActionListener { duplicateSelected() }
    val deleteBtn = JButton("Delete")
    deleteBtn.addActionListener { deleteSelected() }
    val leftTop = JPanel(BorderLayout())
    leftTop.add(JLabel("Sources in library.bib"), BorderLayout.WEST)
    val leftButtons = JPanel()
    leftButtons.add(refresh)
    leftButtons.add(duplicateBtn)
    leftButtons.add(deleteBtn)
    leftTop.add(leftButtons, BorderLayout.EAST)
    leftPanel.add(leftTop, BorderLayout.NORTH)
    leftPanel.add(JScrollPane(entryList), BorderLayout.CENTER)

    val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel)
    split.resizeWeight = 0.35
    split.dividerLocation = 280
    add(split, BorderLayout.CENTER)

    // Initial load
    refreshList()

    // Watch for external changes to library.bib and auto-refresh the list
    installVfsWatcher()
  }

  private fun saveEntry() {
    val type = (typeField.selectedItem as? String)?.trim().orEmpty()
    val key = keyField.text.trim()
    if (type.isEmpty() || key.isEmpty()) {
      Messages.showErrorDialog(project, "Type and Key are required.", "Bibliography")
      return
    }
    val fields = mutableMapOf<String, String>()
    fun putIfNotEmpty(k: String, tf: JTextField) { val v = tf.text.trim(); if (v.isNotEmpty()) fields[k] = v }
    putIfNotEmpty("author", authorField)
    putIfNotEmpty("title", titleField)
    putIfNotEmpty("year", yearField)
    putIfNotEmpty("journal", journalField)
    putIfNotEmpty("booktitle", booktitleField)
    putIfNotEmpty("publisher", publisherField)
    putIfNotEmpty("doi", doiField)
    putIfNotEmpty("url", urlField)

    val ok = project.getService(BibLibraryService::class.java).upsertEntry(
      BibLibraryService.BibEntry(type, key, fields)
    )
    if (ok) {
      Messages.showInfoMessage(project, "Saved to library.bib", "Bibliography")
      refreshList(selectKey = key)
    }
    else Messages.showErrorDialog(project, "Failed to save entry.", "Bibliography")
  }

  private fun openLibrary() {
    val path = project.getService(BibLibraryService::class.java).ensureLibraryExists()
    if (path == null) {
      Messages.showErrorDialog(project, "Project base path not found.", "Bibliography")
      return
    }
    val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path.toFile())
    if (vFile != null) {
      com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vFile, true)
    } else {
      Messages.showErrorDialog(project, "Unable to open library.bib.", "Bibliography")
    }
  }

  private fun importByDoiOrUrl() {
    val id = importField.text.trim()
    if (id.isEmpty()) {
      Messages.showErrorDialog(project, "Enter a DOI, URL, or title.", "Bibliography")
      return
    }
    val preferredKey = keyField.text.trim().ifEmpty { null }
    val svc = project.getService(BibLibraryService::class.java)
    val entry = svc.importFromAny(id, preferredKey)
    if (entry != null) {
      Messages.showInfoMessage(project, "Imported ${entry.key}", "Bibliography")
      refreshList(selectKey = entry.key)
      loadEntryIntoForm(entry)
    } else {
      Messages.showErrorDialog(project, "Import failed. Enter a DOI, title, URL, or direct PDF URL.", "Bibliography")
    }
  }

  private fun importViaAi() {
    val id = importField.text.trim()
    if (id.isEmpty()) {
      Messages.showErrorDialog(project, "Enter an ISBN, DOI, or URL to lookup.", "Bibliography")
      return
    }
    // Always try deterministic sources first, AI as last resort
    val svc = project.getService(BibLibraryService::class.java)
    val preferredKey = keyField.text.trim().ifEmpty { null }
    val resolved = svc.importFromAny(id, preferredKey)
    if (resolved != null) {
      Messages.showInfoMessage(project, "Imported ${resolved.key}", "Bibliography")
      refreshList(selectKey = resolved.key)
      loadEntryIntoForm(resolved)
      return
    }
    if (AiBibliographyLookup.isAiAvailable()) {
      val entry = AiBibliographyLookup.lookup(project, id)
      if (entry != null) {
        val key = preferredKey ?: entry.key
        val saved = svc.upsertEntry(entry.copy(key = key))
        if (saved) {
          Messages.showInfoMessage(project, "Imported ${key}", "Bibliography")
          refreshList(selectKey = key)
          loadEntryIntoForm(entry.copy(key = key))
          return
        }
      }
    }
    Messages.showErrorDialog(project, "Lookup failed across sources (OpenLibrary, Google Books, Crossref, OCLC WorldCat, BNB, openBD, LOC) and AI.", "Bibliography")
  }

  private fun refreshList(selectKey: String? = null) {
    val svc = project.getService(BibLibraryService::class.java)
    svc.ensureLibraryExists()
    val entries = svc.readEntries()
    listModel.clear()
    entries.sortedWith(compareBy({ it.fields["author"] ?: "" }, { it.yearOrEmpty() }, { it.key })).forEach { listModel.addElement(it) }
    if (selectKey != null) {
      val idx = (0 until listModel.size()).firstOrNull { listModel.elementAt(it).key == selectKey }
      if (idx != null) entryList.selectedIndex = idx
    }
  }

  private fun loadEntryIntoForm(e: BibLibraryService.BibEntry) {
    typeField.selectedItem = e.type
    keyField.text = e.key
    authorField.text = e.fields["author"] ?: ""
    titleField.text = e.fields["title"] ?: ""
    yearField.text = e.fields["year"] ?: ""
    journalField.text = e.fields["journal"] ?: ""
    booktitleField.text = e.fields["booktitle"] ?: ""
    publisherField.text = e.fields["publisher"] ?: ""
    doiField.text = e.fields["doi"] ?: ""
    urlField.text = e.fields["url"] ?: ""
  }

  private fun duplicateSelected() {
    val selected = entryList.selectedValue ?: run {
      Messages.showErrorDialog(project, "Select an entry to duplicate.", "Bibliography")
      return
    }
    val svc = project.getService(BibLibraryService::class.java)
    val defaultKey = svc.suggestDuplicateKey(selected.key)
    val newKey = Messages.showInputDialog(project, "New key for duplicate:", "Duplicate Entry", null, defaultKey, null)
    if (newKey.isNullOrBlank()) return
    val ok = svc.upsertEntry(selected.copy(key = newKey.trim()))
    if (ok) {
      refreshList(selectKey = newKey.trim())
    } else {
      Messages.showErrorDialog(project, "Failed to duplicate entry.", "Bibliography")
    }
  }

  private fun deleteSelected() {
    val selected = entryList.selectedValue ?: run {
      Messages.showErrorDialog(project, "Select an entry to delete.", "Bibliography")
      return
    }
    val confirm = Messages.showYesNoDialog(
      project,
      "Delete '${selected.key}' (${selected.type}) from library.bib?",
      "Delete Entry",
      "Delete",
      "Cancel",
      null
    )
    if (confirm != Messages.YES) return
    val svc = project.getService(BibLibraryService::class.java)
    val ok = svc.deleteEntry(selected.type, selected.key)
    if (ok) {
      refreshList()
    } else {
      Messages.showErrorDialog(project, "Failed to delete entry.", "Bibliography")
    }
  }

  private fun installVfsWatcher() {
    val svc = project.getService(BibLibraryService::class.java)
    val target = svc.libraryPath() ?: return
    val targetPath = target.toAbsolutePath().normalize().toString()
    val conn = project.messageBus.connect(this)
    conn.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: MutableList<out VFileEvent>) {
        val hit = events.any { e ->
          val p = (e.file?.path ?: e.path) ?: return@any false
          pathsEqual(p, targetPath)
        }
        if (hit) {
          ApplicationManager.getApplication().invokeLater { refreshList() }
        }
      }
    })
  }

  private fun pathsEqual(a: String, b: String): Boolean {
    val os = System.getProperty("os.name").lowercase()
    return if (os.contains("win")) a.equals(b, ignoreCase = true) else a == b
  }

  override fun dispose() {
    // messageBus connection is tied to this as a parent disposable; nothing else to do
  }
}

private fun BibLibraryService.BibEntry.yearOrEmpty(): String = fields["year"] ?: ""
