package com.samcaldwell.latex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.icons.AllIcons
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
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Paths
import java.nio.file.Files
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import javax.swing.BorderFactory
import javax.swing.border.Border
import java.awt.Color

class BibliographyToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = BibliographyForm(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}

class BibliographyForm(private val project: Project) : JPanel(BorderLayout()), Disposable {
  private val statusLabel = JLabel()
  private var dirty: Boolean = false
  private var isLoading: Boolean = false
  private val defaultBorders = mutableMapOf<JComponent, Border?>()
  private val defaultLabelColors = mutableMapOf<JComponent, Color>()
  private var currentKey: String? = null
  private var currentType: String? = null
  private val listModel = DefaultListModel<BibLibraryService.BibEntry>()
  private val formPanel = JPanel(GridBagLayout())
  private var formRow = 0
  private data class FieldRow(val label: JComponent, val field: JComponent)
  private val fieldRows = mutableMapOf<String, FieldRow>()
  private val entryList = JList(listModel).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        if (value is BibLibraryService.BibEntry) {
          val title = value.fields["title"]?.takeIf { it.isNotBlank() }
          val firstAuthor = value.fields["author"]?.let { authors ->
            val parts = authors.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE))
            parts.firstOrNull()?.trim()
          }?.takeIf { !it.isNullOrBlank() }
          c.text = when {
            title != null && firstAuthor != null -> "$title — $firstAuthor"
            title != null -> title
            firstAuthor != null -> firstAuthor
            else -> value.key
          }
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
  private val typeField = JComboBox(arrayOf(
    // Common BibTeX types
    "article", "book", "inproceedings", "misc",
    // Expanded types requested
    "music", "movie/film", "tv/radio broadcast", "website", "journal", "speech",
    "thesis (or dissertation)", "patent", "personal communication", "dictionary entry",
    "conference paper", "image", "legislation", "video", "song", "report", "regulation"
  ))
  private data class AuthorName(val family: String, val given: String)
  private val authorFamilyField = JTextField()
  private val authorGivenField = JTextField()
  private val authorsModel = DefaultListModel<AuthorName>()
  private val authorsList = JList(authorsModel).apply {
    visibleRowCount = 4
    cellRenderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        if (value is AuthorName) {
          c.text = if (value.given.isNotBlank()) "${value.family}, ${value.given}" else value.family
        }
        return c
      }
    }
  }
  private val titleField = JTextField()
  private val yearField = JTextField()
  private val journalField = JTextField()
  private val publisherField = JTextField()
  private val doiField = JTextField()
  private val urlField = JTextField()
  private val verifiedCheck = JCheckBox()
  private val importField = JTextField()

  init {
    fun addRow(label: String, comp: JComponent) {
      val lc = GridBagConstraints().apply {
        gridx = 0; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      val fc = GridBagConstraints().apply {
        gridx = 1; gridy = formRow; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      formPanel.add(JLabel(label), lc)
      formPanel.add(comp, fc)
      formRow++
    }
    fun addFieldRow(key: String, label: String, comp: JComponent) {
      val lc = GridBagConstraints().apply {
        gridx = 0; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      val fc = GridBagConstraints().apply {
        gridx = 1; gridy = formRow; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      val lab = JLabel(label)
      formPanel.add(lab, lc)
      formPanel.add(comp, fc)
      fieldRows[key] = FieldRow(lab, comp)
      // track default label color for validation state
      defaultLabelColors.putIfAbsent(lab, lab.foreground)
      formRow++
    }

    addRow("Type", typeField)
    // Author composite input: Family + Given + Add button
    val addAuthorBtn = JButton("+")
    authorFamilyField.columns = 12
    authorGivenField.columns = 12
    val authorInput = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
    authorInput.add(JLabel("Family:"))
    authorInput.add(authorFamilyField)
    authorInput.add(JLabel("Given:"))
    authorInput.add(authorGivenField)
    authorInput.add(addAuthorBtn)
    addFieldRow("author_input", "Author", authorInput)
    val authorsScroll = JScrollPane(authorsList).apply { preferredSize = java.awt.Dimension(200, 80) }
    addFieldRow("author_list", "Authors", authorsScroll)
    addAuthorBtn.addActionListener { addAuthorFromFields() }
    addFieldRow("title", "Title", titleField)
    // Year: limit to 4 characters and digits only, set visual width to 4
    yearField.columns = 4
    (yearField.document as? javax.swing.text.AbstractDocument)?.documentFilter = object : javax.swing.text.DocumentFilter() {
      override fun insertString(fb: javax.swing.text.DocumentFilter.FilterBypass, offset: Int, string: String?, attr: javax.swing.text.AttributeSet?) {
        if (string == null) return
        val filtered = string.filter { it.isDigit() }
        val newLen = fb.document.length + filtered.length
        if (filtered.isNotEmpty() && newLen <= 4) super.insertString(fb, offset, filtered, attr)
      }
      override fun replace(fb: javax.swing.text.DocumentFilter.FilterBypass, offset: Int, length: Int, text: String?, attrs: javax.swing.text.AttributeSet?) {
        val current = fb.document.getText(0, fb.document.length)
        val prefix = current.substring(0, offset)
        val suffix = current.substring(offset + length)
        val t = (text ?: "").filter { it.isDigit() }
        val candidate = (prefix + t + suffix)
        if (candidate.length <= 4) super.replace(fb, offset, length, t, attrs)
      }
    }
    addFieldRow("year", "Year", yearField)
    addFieldRow("journal", "Journal", journalField)
    addFieldRow("publisher", "Publisher", publisherField)
    addFieldRow("doi", "DOI", doiField)
    // DOI: make it only as wide as needed for a DOI
    doiField.columns = 28
    val gbl = formPanel.layout as GridBagLayout
    run {
      val gc = gbl.getConstraints(doiField)
      gc.weightx = 0.0
      gc.fill = GridBagConstraints.NONE
      gc.anchor = GridBagConstraints.WEST
      gbl.setConstraints(doiField, gc)
    }
    addFieldRow("url", "URL", urlField)
    addFieldRow("verified", "Verified", verifiedCheck)

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

    val topPanel = JPanel(BorderLayout())
    val toolBar = JToolBar().apply {
      isFloatable = false
      val newBtn = JButton(AllIcons.General.Add).apply {
        toolTipText = "New entry"
        addActionListener { newEntryForm() }
      }
      val saveBtn = JButton(AllIcons.Actions.MenuSaveall).apply {
        toolTipText = "Save"
        addActionListener { saveEntry() }
      }
      val openBtn = JButton(AllIcons.Actions.MenuOpen).apply {
        toolTipText = "Open .bib file"
        addActionListener { openSpecificLibrary() }
      }
      val viewBtn = JButton(AllIcons.Actions.Preview).apply {
        toolTipText = "View/Edit bibliography table"
        addActionListener { openBrowserDialog() }
      }
      add(newBtn)
      add(saveBtn)
      add(openBtn)
      add(viewBtn)
    }
    topPanel.add(toolBar, BorderLayout.NORTH)
    topPanel.add(JScrollPane(formPanel), BorderLayout.CENTER)
    topPanel.add(buttonPanel, BorderLayout.SOUTH)

    val bottomPanel = JPanel(BorderLayout())
    val refresh = JButton("Refresh")
    refresh.addActionListener { refreshList() }
    val deleteBtn = JButton("Delete")
    deleteBtn.addActionListener { deleteSelected() }
    val bottomTop = JPanel(BorderLayout())
    bottomTop.add(JLabel("Sources in library.bib"), BorderLayout.WEST)
    val bottomButtons = JPanel()
    bottomButtons.add(refresh)
    bottomButtons.add(deleteBtn)
    bottomTop.add(bottomButtons, BorderLayout.EAST)
    bottomPanel.add(bottomTop, BorderLayout.NORTH)
    bottomPanel.add(JScrollPane(entryList), BorderLayout.CENTER)

    val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel)
    split.resizeWeight = 0.75
    split.dividerLocation = 420
    add(split, BorderLayout.CENTER)

    // Footer status bar
    val statusPanel = JPanel(BorderLayout())
    statusLabel.font = statusLabel.font.deriveFont(statusLabel.font.size2D - 1f)
    statusPanel.add(statusLabel, BorderLayout.WEST)
    add(statusPanel, BorderLayout.SOUTH)

    // React to type selection to show context-specific fields and mark dirty
    typeField.addActionListener {
      updateVisibleFields()
      markDirtyFromUser()
    }

    // Mark dirty on edits
    fun JTextField.onUserChange() {
      this.document.addDocumentListener(object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = markDirtyFromUser()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = markDirtyFromUser()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = markDirtyFromUser()
      })
    }
    authorFamilyField.onUserChange()
    authorGivenField.onUserChange()
    titleField.onUserChange()
    yearField.onUserChange()
    journalField.onUserChange()
    publisherField.onUserChange()
    doiField.onUserChange()
    urlField.onUserChange()
    verifiedCheck.addActionListener { markDirtyFromUser() }

    // Track default borders for validation highlighting
    fun trackBorder(c: JComponent) { defaultBorders.putIfAbsent(c, c.border) }
    listOf(authorFamilyField, authorGivenField, titleField, yearField, journalField, publisherField, doiField, urlField, authorsList).forEach { trackBorder(it) }
    // Track default label colors for all fields added
    fieldRows.values.forEach { row -> defaultLabelColors.putIfAbsent(row.label, (row.label as JComponent).foreground) }

    // Initial load and initial field visibility
    updateVisibleFields()
    refreshList()
    updateStatus()
    validateForm()

    // Watch for external changes to library.bib and auto-refresh the list
    installVfsWatcher()
  }

  private fun saveEntry() {
    if (!validateForm()) {
      Messages.showErrorDialog(project, "Please correct highlighted fields.", "Bibliography")
      return
    }
    val type = (typeField.selectedItem as? String)?.trim().orEmpty()
    if (type.isEmpty()) {
      Messages.showErrorDialog(project, "Type is required.", "Bibliography")
      return
    }
    val fields = mutableMapOf<String, String>()
    val allowed = visibleKeysForSelectedType()
    fun putIfNotEmpty(k: String, tf: JTextField) { if (k in allowed) { val v = tf.text.trim(); if (v.isNotEmpty()) fields[k] = v } }
    if ("author" in allowed) {
      val authors = collectAuthors()
      if (authors.isNotEmpty()) fields["author"] = authors.joinToString(" and ") { n ->
        if (n.given.isNotBlank()) "${n.family}, ${n.given}" else n.family
      }
    }
    if ("title" in allowed) putIfNotEmpty("title", titleField)
    if ("year" in allowed) putIfNotEmpty("year", yearField)
    if ("journal" in allowed) putIfNotEmpty("journal", journalField)
    if ("publisher" in allowed) putIfNotEmpty("publisher", publisherField)
    if ("doi" in allowed) putIfNotEmpty("doi", doiField)
    if ("url" in allowed) putIfNotEmpty("url", urlField)
    // Always track source; verified only if visible
    fields["source"] = "manual entry"
    if ("verified" in allowed) fields["verified"] = if (verifiedCheck.isSelected) "true" else "false"

    val generatedKey = currentKey ?: java.util.UUID.randomUUID().toString()
    // If editing and type changed, remove the old entry before writing the new one
    val svc = project.getService(BibLibraryService::class.java)
    val prevType = currentType
    if (currentKey != null && prevType != null && prevType != type) {
      svc.deleteEntry(prevType, currentKey!!)
    }
    val ok = svc.upsertEntry(
      BibLibraryService.BibEntry(type, generatedKey, fields)
    )
    if (ok) {
      Messages.showInfoMessage(project, "Saved to library.bib", "Bibliography")
      refreshList(selectKey = generatedKey)
      currentKey = generatedKey
      currentType = type
      dirty = false
      updateStatus()
    }
    else Messages.showErrorDialog(project, "Failed to save entry.", "Bibliography")
  }

  private fun newEntryForm() {
    currentKey = null
    currentType = null
    typeField.selectedIndex = 0
    updateVisibleFields()
    authorFamilyField.text = ""
    authorGivenField.text = ""
    authorsModel.clear()
    titleField.text = ""
    yearField.text = ""
    journalField.text = ""
    publisherField.text = ""
    doiField.text = ""
    urlField.text = ""
    verifiedCheck.isSelected = false
  }

  private fun openSpecificLibrary() {
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("bib")
      .withTitle("Open Bibliography (.bib)")
    val vFile = FileChooser.chooseFile(descriptor, project, null) ?: return
    FileEditorManager.getInstance(project).openFile(vFile, true)
  }

  private fun openBrowserDialog() {
    BibliographyBrowserDialog(project).show()
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
      updateStatus()
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
    val svc2 = project.getService(BibLibraryService::class.java)
    val entry = svc2.importFromAny(id, null)
    if (entry != null) {
      Messages.showInfoMessage(project, "Imported ${entry.key}", "Bibliography")
      refreshList(selectKey = entry.key)
      loadEntryIntoForm(entry)
      dirty = false
      updateStatus()
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
    val svc3 = project.getService(BibLibraryService::class.java)
    val resolved = svc3.importFromAny(id, null)
    if (resolved != null) {
      Messages.showInfoMessage(project, "Imported ${resolved.key}", "Bibliography")
      refreshList(selectKey = resolved.key)
      loadEntryIntoForm(resolved)
      dirty = false
      updateStatus()
      return
    }
    val aiEnabled = com.intellij.openapi.application.ApplicationManager.getApplication().getService(LookupSettingsService::class.java).isAiFallbackEnabled()
    if (aiEnabled && AiBibliographyLookup.isAiAvailable()) {
      val entry = AiBibliographyLookup.lookup(project, id)
      if (entry != null) {
        val key = entry.key
        val augmented = entry.copy(key = key, fields = entry.fields + mapOf("source" to "automated (JetBrains AI)", "verified" to "false"))
        val saved = project.getService(BibLibraryService::class.java).upsertEntry(augmented)
        if (saved) {
          Messages.showInfoMessage(project, "Imported ${key}", "Bibliography")
          refreshList(selectKey = key)
          loadEntryIntoForm(augmented)
          dirty = false
          updateStatus()
          return
        }
      }
    }
    Messages.showErrorDialog(project, "Lookup failed across sources (OpenLibrary, Google Books, Crossref, OCLC WorldCat, BNB, openBD, LOC)" + if (aiEnabled) " and AI." else ".", "Bibliography")
  }

  private fun refreshList(selectKey: String? = null) {
    val svc = project.getService(BibLibraryService::class.java)
    svc.ensureLibraryExists()
    val entries = svc.readEntries()
    listModel.clear()
    val sorted = entries.sortedWith(java.util.Comparator<BibLibraryService.BibEntry> { a, b ->
      val ta = a.fields["title"]?.trim().orEmpty()
      val tb = b.fields["title"]?.trim().orEmpty()
      val cmp = String.CASE_INSENSITIVE_ORDER.compare(ta, tb)
      if (cmp != 0) cmp else a.key.compareTo(b.key)
    })
    sorted.forEach { listModel.addElement(it) }
    if (selectKey != null) {
      val idx = (0 until listModel.size()).firstOrNull { listModel.elementAt(it).key == selectKey }
      if (idx != null) entryList.selectedIndex = idx
    }
    updateStatus()
  }

  private fun loadEntryIntoForm(e: BibLibraryService.BibEntry) {
    isLoading = true
    try {
      currentKey = e.key
      currentType = e.type
      typeField.selectedItem = e.type
      updateVisibleFields()
      // Populate authors list from BibTeX author string
      authorsModel.clear()
      val auth = e.fields["author"]
      if (!auth.isNullOrBlank()) parseAuthors(auth).forEach { authorsModel.addElement(it) }
      authorFamilyField.text = ""
      authorGivenField.text = ""
      titleField.text = e.fields["title"] ?: e.fields["booktitle"] ?: ""
      val yr = e.fields["year"]?.let { yrStr ->
        val m = Regex("\\b(\\d{4})\\b").find(yrStr)
        m?.groupValues?.getOrNull(1) ?: ""
      } ?: ""
      yearField.text = yr
      journalField.text = e.fields["journal"] ?: ""
      // If legacy entries have only booktitle, surface it as title
      publisherField.text = e.fields["publisher"] ?: ""
      doiField.text = e.fields["doi"] ?: ""
      urlField.text = e.fields["url"] ?: ""
      verifiedCheck.isSelected = (e.fields["verified"] ?: "false").equals("true", ignoreCase = true)
      dirty = false
    } finally {
      isLoading = false
      updateStatus()
    }
  }

  private fun addAuthorFromFields() {
    val family = authorFamilyField.text.trim()
    val given = authorGivenField.text.trim()
    if (family.isEmpty() && given.isEmpty()) return
    authorsModel.addElement(AuthorName(family, given))
    authorFamilyField.text = ""
    authorGivenField.text = ""
    authorFamilyField.requestFocusInWindow()
    markDirtyFromUser()
    validateForm()
  }

  private fun collectAuthors(): List<AuthorName> {
    val list = mutableListOf<AuthorName>()
    for (i in 0 until authorsModel.size()) list += authorsModel.elementAt(i)
    // Include pending values not yet added via +
    val fam = authorFamilyField.text.trim()
    val giv = authorGivenField.text.trim()
    if (fam.isNotEmpty() || giv.isNotEmpty()) list += AuthorName(fam, giv)
    // Ensure at least family names are present
    return list.filter { it.family.isNotBlank() || it.given.isNotBlank() }
  }

  private fun parseAuthors(s: String): List<AuthorName> {
    val parts = s.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE))
      .map { it.trim() }
      .filter { it.isNotEmpty() }
    return parts.map { name ->
      if ("," in name) {
        val idx = name.indexOf(',')
        val family = name.substring(0, idx).trim()
        val given = name.substring(idx + 1).trim()
        AuthorName(family, given)
      } else {
        val tokens = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) AuthorName("", "") else AuthorName(tokens.last(), tokens.dropLast(1).joinToString(" "))
      }
    }
  }

  private fun visibleKeysForSelectedType(): Set<String> {
    val t = (typeField.selectedItem as? String)?.lowercase()?.trim() ?: ""
    return when (t) {
      "article" -> setOf("author", "title", "year", "journal", "doi", "url", "verified")
      "book" -> setOf("author", "title", "year", "publisher", "doi", "url", "verified")
      "inproceedings", "conference paper" -> setOf("author", "title", "year", "doi", "url", "verified")
      "misc" -> setOf("author", "title", "year", "url", "verified")
      "thesis (or dissertation)", "report" -> setOf("author", "title", "year", "publisher", "url", "verified")
      "patent" -> setOf("author", "title", "year", "publisher", "url", "verified")
      "dictionary entry" -> setOf("author", "title", "year", "publisher", "url", "verified")
      "legislation", "regulation" -> setOf("title", "year", "publisher", "url", "verified")
      "music", "song", "movie/film", "video", "tv/radio broadcast", "speech", "image", "website", "journal", "personal communication" -> setOf("author", "title", "year", "url", "verified")
      else -> setOf("author", "title", "year", "url", "verified")
    }
  }

  private fun updateVisibleFields() {
    val base = visibleKeysForSelectedType()
    val show = base + (if ("author" in base) setOf("author_input", "author_list") else emptySet())
    for ((key, row) in fieldRows) {
      val visible = key in show
      row.label.isVisible = visible
      row.field.isVisible = visible
    }
    formPanel.revalidate()
    formPanel.repaint()
    validateForm()
  }

  private fun markDirtyFromUser() {
    if (!isLoading) {
      dirty = true
      updateStatus()
      validateForm()
    }
  }

  private fun updateStatus() {
    val svc = project.getService(BibLibraryService::class.java)
    val lib = svc.libraryPath()
    val libPathStr = lib?.toAbsolutePath()?.normalize()?.toString() ?: "<no library>"
    val updatedTs = when {
      currentKey != null -> {
        val e = svc.readEntries().firstOrNull { it.key == currentKey }
        e?.fields?.get("modified") ?: safeFileMtime(lib) ?: ""
      }
      else -> safeFileMtime(lib) ?: ""
    }
    val state = if (dirty) "unsaved" else "saved"
    statusLabel.text = "$libPathStr — updated: $updatedTs ($state)"
  }

  private fun safeFileMtime(p: java.nio.file.Path?): String? = try {
    if (p == null || !Files.exists(p)) null else java.time.Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()).toString()
  } catch (_: Throwable) { null }

  private fun validateForm(): Boolean {
    // reset tooltips and label colors
    fieldRows.values.forEach { row ->
      row.label.foreground = defaultLabelColors[row.label] ?: row.label.foreground
      row.label.toolTipText = null
    }
    listOf(authorFamilyField, authorGivenField, authorsList, titleField, yearField, journalField, publisherField, doiField, urlField).forEach { c ->
      c.toolTipText = null
    }

    val errBorder = BorderFactory.createLineBorder(Color(0xD32F2F))

    fun flagError(key: String, comp: JComponent, message: String) {
      comp.border = errBorder
      comp.toolTipText = message
      val row = fieldRows[key]
      if (row != null) {
        row.label.foreground = Color(0xD32F2F)
        row.label.toolTipText = message
      }
    }

    fun clearError(comp: JComponent, key: String? = null) {
      comp.border = defaultBorders[comp]
      comp.toolTipText = null
      if (key != null) {
        val row = fieldRows[key]
        if (row != null) {
          row.label.foreground = defaultLabelColors[row.label] ?: row.label.foreground
          row.label.toolTipText = null
        }
      }
    }

    var valid = true
    val t = (typeField.selectedItem as? String)?.lowercase()?.trim() ?: ""
    val required = requiredKeysForType(t)
    val visible = visibleKeysForSelectedType()

    // Title
    val titleVal = titleField.text.trim()
    if (titleVal.isEmpty()) { flagError("title", titleField, "Title is required"); valid = false } else clearError(titleField, "title")

    // Authors
    if ("author" in visible) {
      val authorList = collectAuthors()
      val hasAuthor = authorList.any { it.family.isNotBlank() || it.given.isNotBlank() }
      val authorRequired = "author" in required
      if (authorRequired && !hasAuthor) {
        flagError("author_input", authorFamilyField, "At least one author required")
        flagError("author_input", authorGivenField, "At least one author required")
        flagError("author_list", authorsList, "Add authors using +")
        valid = false
      } else {
        clearError(authorFamilyField)
        clearError(authorGivenField)
        clearError(authorsList)
      }
    }

    // Year
    val yearVal = yearField.text.trim()
    val yearRequired = "year" in required
    if (yearRequired && yearVal.isEmpty()) { flagError("year", yearField, "Year is required"); valid = false }
    else if (yearVal.isNotEmpty() && !yearVal.matches(Regex("\\d{4}"))) { flagError("year", yearField, "Year must be 4 digits"); valid = false }
    else clearError(yearField, "year")

    // Journal
    if ("journal" in visible) {
      val req = "journal" in required
      val v = journalField.text.trim()
      if (req && v.isEmpty()) { flagError("journal", journalField, "Journal is required"); valid = false } else clearError(journalField, "journal")
    }

    // Publisher
    if ("publisher" in visible) {
      val req = "publisher" in required
      val v = publisherField.text.trim()
      if (req && v.isEmpty()) { flagError("publisher", publisherField, "Publisher is required"); valid = false } else clearError(publisherField, "publisher")
    }

    // DOI format
    val doiVal = doiField.text.trim()
    if (doiVal.isNotEmpty() && !doiVal.matches(Regex("(?i)^10\\.\\S+/.+"))) { flagError("doi", doiField, "Invalid DOI format") ; valid = false } else clearError(doiField, "doi")

    // URL format
    val urlVal = urlField.text.trim()
    val urlOk = if (urlVal.isEmpty()) true else try {
      (urlVal.startsWith("http://") || urlVal.startsWith("https://")) && java.net.URI(urlVal).isAbsolute
    } catch (_: Throwable) { false }
    if (!urlOk) { flagError("url", urlField, "URL must start with http:// or https://") ; valid = false } else clearError(urlField, "url")

    return valid
  }

  private fun requiredKeysForType(t: String): Set<String> = when (t) {
    // Scholarly article requires journal and year
    "article" -> setOf("title", "author", "year", "journal")
    // Book requires publisher and year
    "book" -> setOf("title", "author", "year", "publisher")
    // Proceedings: require author, title, year
    "inproceedings", "conference paper" -> setOf("title", "author", "year")
    // Thesis/report/patent/dictionary entry: require author, title, year, publisher
    "thesis (or dissertation)", "report", "patent", "dictionary entry" -> setOf("title", "author", "year", "publisher")
    // Journal document type (non-article) require author, title, year
    "journal" -> setOf("title", "author", "year")
    // Legislation/regulation: title, year, publisher (issuing body)
    "legislation", "regulation" -> setOf("title", "year", "publisher")
    // Media and misc: title required, author often desirable but optional; year optional
    else -> setOf("title")
  }

  // Duplicate functionality removed per data model: duplicates not allowed

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
          ApplicationManager.getApplication().invokeLater {
            refreshList()
            updateStatus()
          }
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

private class BibliographyBrowserDialog(private val project: Project) : DialogWrapper(project, true) {
  private val svc = project.getService(BibLibraryService::class.java)
  private val model = BrowserTableModel(loadEntries())
  private val table = JTable(model)
  private val sorter = TableRowSorter<BrowserTableModel>(model)
  private val searchField = JTextField()
  private val typeFilter = JComboBox<String>()

  init {
    title = "Bibliography Browser"
    init()
    table.rowSorter = sorter
    table.fillsViewportHeight = true
    table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
    // Type column editor as combo
    val types = arrayOf(
      "article", "book", "inproceedings", "misc",
      "music", "movie/film", "tv/radio broadcast", "website", "journal", "speech",
      "thesis (or dissertation)", "patent", "personal communication", "dictionary entry",
      "conference paper", "image", "legislation", "video", "song", "report", "regulation"
    )
    val typeEditor = DefaultCellEditor(JComboBox(types))
    table.columnModel.getColumn(1).cellEditor = typeEditor
    // Verified as checkbox
    table.columnModel.getColumn(9).cellEditor = DefaultCellEditor(JCheckBox())

    // Build type filter options
    val allTypes = linkedSetOf("All")
    model.rows.map { it.currentType }.toCollection(allTypes)
    typeFilter.model = DefaultComboBoxModel(allTypes.toTypedArray())
    typeFilter.selectedItem = "All"
    typeFilter.addActionListener { applyFilter() }

    searchField.columns = 18
    searchField.toolTipText = "Search"
    searchField.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = applyFilter()
      override fun removeUpdate(e: DocumentEvent) = applyFilter()
      override fun changedUpdate(e: DocumentEvent) = applyFilter()
    })
  }

  override fun createCenterPanel(): JComponent {
    val panel = JPanel(BorderLayout(6, 6))
    val top = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 6))
    top.add(JLabel("Type:"))
    top.add(typeFilter)
    top.add(Box.createHorizontalStrut(12))
    top.add(JLabel("Search:"))
    top.add(searchField)
    panel.add(top, BorderLayout.NORTH)
    panel.add(JScrollPane(table), BorderLayout.CENTER)

    val btns = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 6))
    val deleteBtn = JButton("Delete Selected")
    deleteBtn.addActionListener { deleteSelected() }
    val saveBtn = JButton("Save Changes")
    saveBtn.addActionListener { saveChanges() }
    val refreshBtn = JButton("Refresh")
    refreshBtn.addActionListener { refresh() }
    btns.add(refreshBtn)
    btns.add(deleteBtn)
    btns.add(saveBtn)
    panel.add(btns, BorderLayout.SOUTH)
    return panel
  }

  override fun createActions(): Array<Action> {
    return arrayOf(cancelAction)
  }

  private fun loadEntries(): List<BibLibraryService.BibEntry> = svc.readEntries()

  private fun refresh() {
    model.setEntries(loadEntries())
    // Update filter choices
    val allTypes = linkedSetOf("All")
    model.rows.map { it.currentType }.toCollection(allTypes)
    typeFilter.model = DefaultComboBoxModel(allTypes.toTypedArray())
    applyFilter()
  }

  private fun applyFilter() {
    val q = searchField.text.trim().lowercase()
    val typeSel = (typeFilter.selectedItem as? String) ?: "All"
    sorter.rowFilter = object : RowFilter<BrowserTableModel, Int>() {
      override fun include(entry: Entry<out BrowserTableModel, out Int>): Boolean {
        val r = model.rows[entry.identifier]
        if (typeSel != "All" && !r.currentType.equals(typeSel, true)) return false
        if (q.isEmpty()) return true
        return r.matches(q)
      }
    }
  }

  private fun deleteSelected() {
    val viewRows = table.selectedRows
    if (viewRows.isEmpty()) return
    val toDelete = viewRows.map { vr -> model.rows[table.convertRowIndexToModel(vr)] }
    for (row in toDelete) {
      svc.deleteEntry(row.originalType, row.key)
    }
    refresh()
  }

  private fun saveChanges() {
    // Merge edits back into service while preserving unknown fields
    val current = svc.readEntries().associateBy { it.type + "\u0000" + it.key }
    var okAll = true
    for (row in model.rows) {
      if (!row.dirty) continue
      val orig = current[row.originalType + "\u0000" + row.key]
      val baseFields = orig?.fields?.toMutableMap() ?: mutableMapOf()
      fun setOrRemove(k: String, v: String?) {
        if (v.isNullOrBlank()) baseFields.remove(k) else baseFields[k] = v
      }
      setOrRemove("author", row.author)
      setOrRemove("title", row.title)
      setOrRemove("year", row.year)
      setOrRemove("journal", row.journal)
      setOrRemove("publisher", row.publisher)
      setOrRemove("doi", row.doi)
      setOrRemove("url", row.url)
      if (row.verified != null) baseFields["verified"] = if (row.verified == true) "true" else "false"
      // Preserve source; if missing, mark it
      baseFields.putIfAbsent("source", "manual edit (browser)")
      val saved = svc.upsertEntry(BibLibraryService.BibEntry(row.currentType, row.key, baseFields))
      okAll = okAll && saved
      row.commit()
    }
    if (!okAll) Messages.showWarningDialog(project, "Some rows failed to save.", "Bibliography") else refresh()
  }

  private class BrowserTableModel(entries: List<BibLibraryService.BibEntry>) : AbstractTableModel() {
    val rows = entries.map { EntryRow(it) }.toMutableList()
    private val cols = arrayOf("Key", "Type", "Title", "Author", "Year", "Journal", "Publisher", "DOI", "URL", "Verified")

    fun setEntries(entries: List<BibLibraryService.BibEntry>) {
      rows.clear()
      rows.addAll(entries.map { EntryRow(it) })
      fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = cols.size
    override fun getColumnName(column: Int): String = cols[column]
    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
      9 -> java.lang.Boolean::class.java
      else -> String::class.java
    }
    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex != 0
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? = when (columnIndex) {
      0 -> rows[rowIndex].key
      1 -> rows[rowIndex].currentType
      2 -> rows[rowIndex].title
      3 -> rows[rowIndex].author
      4 -> rows[rowIndex].year
      5 -> rows[rowIndex].journal
      6 -> rows[rowIndex].publisher
      7 -> rows[rowIndex].doi
      8 -> rows[rowIndex].url
      9 -> rows[rowIndex].verified ?: false
      else -> null
    }
    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
      val r = rows[rowIndex]
      when (columnIndex) {
        1 -> r.currentType = (aValue as? String) ?: r.currentType
        2 -> r.title = (aValue as? String)?.trim().orEmpty()
        3 -> r.author = (aValue as? String)?.trim().orEmpty()
        4 -> r.year = (aValue as? String)?.trim().orEmpty()
        5 -> r.journal = (aValue as? String)?.trim().orEmpty()
        6 -> r.publisher = (aValue as? String)?.trim().orEmpty()
        7 -> r.doi = (aValue as? String)?.trim().orEmpty()
        8 -> r.url = (aValue as? String)?.trim().orEmpty()
        9 -> r.verified = (aValue as? Boolean) ?: false
      }
      r.dirty = true
      fireTableRowsUpdated(rowIndex, rowIndex)
    }
  }

  private class EntryRow(entry: BibLibraryService.BibEntry) {
    val key: String = entry.key
    val originalType: String = entry.type
    var currentType: String = entry.type
    var title: String = entry.fields["title"] ?: entry.fields["booktitle"] ?: ""
    var author: String = entry.fields["author"] ?: ""
    var year: String = entry.fields["year"] ?: ""
    var journal: String = entry.fields["journal"] ?: ""
    var publisher: String = entry.fields["publisher"] ?: ""
    var doi: String = entry.fields["doi"] ?: ""
    var url: String = entry.fields["url"] ?: ""
    var verified: Boolean? = (entry.fields["verified"] ?: "false").equals("true", true)
    var dirty: Boolean = false

    fun matches(q: String): Boolean {
      val hay = listOf(key, currentType, title, author, year, journal, publisher, doi, url).joinToString("\n").lowercase()
      return hay.contains(q)
    }

    fun commit() { dirty = false }
  }
}
