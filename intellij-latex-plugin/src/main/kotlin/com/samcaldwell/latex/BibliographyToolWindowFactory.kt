package com.samcaldwell.latex

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class BibliographyToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = BibliographyForm(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    toolWindow.contentManager.addContent(content)
  }
}

class BibliographyForm(private val project: Project) : JPanel(BorderLayout()) {
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
    val importButton = JButton("Import DOI/URL → BibTeX")
    importButton.addActionListener { importByDoiOrUrl() }
    buttonPanel.add(saveButton)
    buttonPanel.add(openButton)
    buttonPanel.add(JLabel(" DOI/URL:"))
    buttonPanel.add(importField.apply { columns = 18 })
    buttonPanel.add(importButton)

    val rightPanel = JPanel(BorderLayout())
    rightPanel.add(JScrollPane(form), BorderLayout.CENTER)
    rightPanel.add(buttonPanel, BorderLayout.SOUTH)

    val leftPanel = JPanel(BorderLayout())
    val refresh = JButton("Refresh")
    refresh.addActionListener { refreshList() }
    val leftTop = JPanel(BorderLayout())
    leftTop.add(JLabel("Sources in library.bib"), BorderLayout.WEST)
    leftTop.add(refresh, BorderLayout.EAST)
    leftPanel.add(leftTop, BorderLayout.NORTH)
    leftPanel.add(JScrollPane(entryList), BorderLayout.CENTER)

    val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel)
    split.resizeWeight = 0.35
    split.dividerLocation = 280
    add(split, BorderLayout.CENTER)

    // Initial load
    refreshList()
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
      Messages.showErrorDialog(project, "Enter a DOI or doi.org URL.", "Bibliography")
      return
    }
    val preferredKey = keyField.text.trim().ifEmpty { null }
    val svc = project.getService(BibLibraryService::class.java)
    val entry = svc.importFromDoiOrUrl(id, preferredKey)
    if (entry != null) {
      Messages.showInfoMessage(project, "Imported ${entry.key}", "Bibliography")
      refreshList(selectKey = entry.key)
      loadEntryIntoForm(entry)
    } else {
      Messages.showErrorDialog(project, "Import failed. Ensure it's a valid DOI or doi.org URL.", "Bibliography")
    }
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
}

private fun BibLibraryService.BibEntry.yearOrEmpty(): String = fields["year"] ?: ""
