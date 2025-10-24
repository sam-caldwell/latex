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
  private val formPanel = JPanel(GridBagLayout())
  private var formRow = 0
  private data class FieldRow(val label: JComponent, val field: JComponent)
  private val fieldRows = mutableMapOf<String, FieldRow>()
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
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
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
  private val abstractArea = JTextArea().apply {
    lineWrap = true
    wrapStyleWord = true
    rows = 4
  }
  private val keywordField = JTextField()
  private val keywordsModel = DefaultListModel<String>()
  private val keywordsList = JList(keywordsModel).apply {
    visibleRowCount = 4
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
  }
  // Metadata fields
  private val createdField = JTextField().apply {
    isEditable = false
    isEnabled = false
  }
  private val modifiedField = JTextField().apply {
    isEditable = false
    isEnabled = false
  }
  private val sourceField = JTextField()
  private val verifiedByField = JTextField()
  private val verifiedCheck = JCheckBox()
  // Search criteria builder UI (replaces DOI/URL import)
  private data class SearchCriterion(val kind: String, val text: String)
  private val searchInput = JTextField().apply { columns = 18 }
  private val searchTypeCombo = JComboBox(arrayOf("Title", "DOI", "URL", "ISBN", "Author"))
  private val searchCriteriaModel = DefaultListModel<SearchCriterion>()
  private val searchCriteriaList = JList(searchCriteriaModel).apply {
    visibleRowCount = 4
    cellRenderer = object : DefaultListCellRenderer() {
      override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
        val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        if (value is SearchCriterion) c.text = "${value.kind}: ${value.text}"
        return c
      }
    }
  }

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

    // Context menu: delete selected author(s)
    run {
      val popup = JPopupMenu()
      val deleteItem = JMenuItem("Delete")
      deleteItem.addActionListener { removeSelectedAuthors() }
      popup.add(deleteItem)

      fun showPopup(e: java.awt.event.MouseEvent) {
        val idx = authorsList.locationToIndex(e.point)
        if (idx >= 0) {
          // If clicked outside current selection, select the item under cursor
          val sel = authorsList.selectedIndices.toSet()
          if (idx !in sel) authorsList.selectedIndex = idx
        }
        popup.show(authorsList, e.x, e.y)
      }

      authorsList.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
          if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e)) showPopup(e)
        }
        override fun mouseReleased(e: java.awt.event.MouseEvent) {
          if (e.isPopupTrigger) showPopup(e)
        }
      })
    }
    // Title + Year on the same row
    run {
      val titleLabel = JLabel("Title")
      val yearLabel = JLabel("Year")

      val lcTitle = GridBagConstraints().apply {
        gridx = 0; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      val fcTitle = GridBagConstraints().apply {
        gridx = 1; gridy = formRow; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      val lcYear = GridBagConstraints().apply {
        gridx = 2; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.NONE
        anchor = GridBagConstraints.EAST
        insets = Insets(4, 6, 4, 6)
      }
      val fcYear = GridBagConstraints().apply {
        gridx = 3; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.NONE
        anchor = GridBagConstraints.WEST
        insets = Insets(4, 0, 4, 6)
      }

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

      // Add components in one row: Title label, Title field, Year label, Year field
      formPanel.add(titleLabel, lcTitle)
      formPanel.add(titleField, fcTitle)
      formPanel.add(yearLabel, lcYear)
      formPanel.add(yearField, fcYear)

      // Register for validation visibility and label coloring
      fieldRows["title"] = FieldRow(titleLabel, titleField)
      fieldRows["year"] = FieldRow(yearLabel, yearField)
      defaultLabelColors.putIfAbsent(titleLabel, titleLabel.foreground)
      defaultLabelColors.putIfAbsent(yearLabel, yearLabel.foreground)

      formRow++
    }
    // Abstract (multiline)
    val abstractScroll = JScrollPane(abstractArea).apply { preferredSize = java.awt.Dimension(200, 88) }
    addFieldRow("abstract", "Abstract", abstractScroll)
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
    // Keywords input + list
    val addKeywordBtn = JButton("+")
    keywordField.columns = 18
    val keywordInput = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
    keywordInput.add(keywordField)
    keywordInput.add(addKeywordBtn)
    addFieldRow("keyword_input", "Keywords", keywordInput)
    val keywordsScroll = JScrollPane(keywordsList).apply { preferredSize = java.awt.Dimension(200, 80) }
    addFieldRow("keywords_list", "Keyword List", keywordsScroll)
    addKeywordBtn.addActionListener { addKeywordFromField() }

    // Keywords context menu: delete selected keyword(s)
    run {
      val popup = JPopupMenu()
      val deleteItem = JMenuItem("Delete")
      deleteItem.addActionListener { removeSelectedKeywords() }
      popup.add(deleteItem)

      fun showPopup(e: java.awt.event.MouseEvent) {
        val idx = keywordsList.locationToIndex(e.point)
        if (idx >= 0) {
          val sel = keywordsList.selectedIndices.toSet()
          if (idx !in sel) keywordsList.selectedIndex = idx
        }
        popup.show(keywordsList, e.x, e.y)
      }

      keywordsList.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
          if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e)) showPopup(e)
        }
        override fun mouseReleased(e: java.awt.event.MouseEvent) {
          if (e.isPopupTrigger) showPopup(e)
        }
      })
    }
    // --- Metadata section header ---
    run {
      val header = JLabel("Metadata")
      val lc = GridBagConstraints().apply {
        gridx = 0; gridy = formRow; gridwidth = 4; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(8, 6, 2, 6)
      }
      header.font = header.font.deriveFont(header.font.size2D + 0f)
      formPanel.add(header, lc)
      formRow++
      val sep = JSeparator()
      val sc = GridBagConstraints().apply {
        gridx = 0; gridy = formRow; gridwidth = 4; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(0, 6, 6, 6)
      }
      formPanel.add(sep, sc)
      formRow++
    }

    // Metadata fields: created, modified (readonly), source, verified, verified_by
    addFieldRow("created", "Created", createdField)
    addFieldRow("modified", "Modified", modifiedField)
    addFieldRow("source", "Source", sourceField)
    addFieldRow("verified", "Verified", verifiedCheck)
    addFieldRow("verified_by", "Verified By", verifiedByField)

    val buttonPanel = JPanel()
    val addCritBtn = JButton("+")
    addCritBtn.addActionListener { addSearchCriterion() }
    val searchBtn = JButton("Search")
    searchBtn.addActionListener { performSearchByCriteria() }
    buttonPanel.add(JLabel("Search:"))
    buttonPanel.add(searchTypeCombo)
    buttonPanel.add(searchInput)
    buttonPanel.add(addCritBtn)
    buttonPanel.add(searchBtn)

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
    // Bottom search section with criteria list
    val searchSection = JPanel(BorderLayout())
    searchSection.add(buttonPanel, BorderLayout.NORTH)
    val critPanel = JPanel(BorderLayout())
    critPanel.add(JLabel("Criteria"), BorderLayout.WEST)
    // Add right-click delete on criteria list
    run {
      val popup = JPopupMenu()
      val deleteItem = JMenuItem("Delete")
      deleteItem.addActionListener {
        val indices = searchCriteriaList.selectedIndices
        if (indices != null && indices.isNotEmpty()) {
          for (i in indices.sortedDescending()) if (i in 0 until searchCriteriaModel.size()) searchCriteriaModel.remove(i)
        }
      }
      popup.add(deleteItem)
      searchCriteriaList.addMouseListener(object : java.awt.event.MouseAdapter() {
        private fun show(e: java.awt.event.MouseEvent) {
          val idx = searchCriteriaList.locationToIndex(e.point)
          if (idx >= 0) {
            val sel = searchCriteriaList.selectedIndices.toSet()
            if (idx !in sel) searchCriteriaList.selectedIndex = idx
          }
          popup.show(searchCriteriaList, e.x, e.y)
        }
        override fun mousePressed(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e)) show(e) }
        override fun mouseReleased(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) show(e) }
      })
    }
    critPanel.add(JScrollPane(searchCriteriaList).apply { preferredSize = java.awt.Dimension(200, 80) }, BorderLayout.CENTER)
    searchSection.add(critPanel, BorderLayout.CENTER)
    topPanel.add(searchSection, BorderLayout.SOUTH)

    // Main content: form only (list of existing sources removed)
    add(topPanel, BorderLayout.CENTER)

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
    keywordField.onUserChange()
    sourceField.onUserChange()
    verifiedByField.onUserChange()
    // Abstract area changes
    abstractArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = markDirtyFromUser()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = markDirtyFromUser()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = markDirtyFromUser()
    })
    verifiedCheck.addActionListener {
      if (verifiedCheck.isSelected) {
        if (verifiedByField.text.trim().isEmpty()) verifiedByField.text = currentUserName()
      } else {
        verifiedByField.text = ""
      }
      markDirtyFromUser()
    }

    // Track default borders for validation highlighting
    fun trackBorder(c: JComponent) { defaultBorders.putIfAbsent(c, c.border) }
    listOf(authorFamilyField, authorGivenField, titleField, yearField, journalField, publisherField, doiField, urlField, authorsList, abstractArea, keywordField, keywordsList, createdField, modifiedField, sourceField, verifiedByField, verifiedCheck).forEach { trackBorder(it) }
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
    if ("abstract" in allowed) {
      val abs = abstractArea.text.trim()
      if (abs.isNotEmpty()) fields["abstract"] = abs
    }
    if ("keywords" in allowed) {
      val kws = collectKeywords()
      if (kws.isNotEmpty()) fields["keywords"] = kws.joinToString(", ")
    }
    // Source
    if ("source" in allowed) {
      val src = sourceField.text.trim()
      fields["source"] = if (src.isNotEmpty()) src else "manual"
    }
    // Verified and verified_by
    if ("verified" in allowed) {
      val isVerified = verifiedCheck.isSelected
      fields["verified"] = if (isVerified) "true" else "false"
      if ("verified_by" in allowed) {
        if (isVerified) {
          val vb = verifiedByField.text.trim()
          fields["verified_by"] = if (vb.isNotEmpty()) vb else currentUserName()
        }
        // if not verified, omit verified_by so it gets removed
      }
    }

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
    abstractArea.text = ""
    keywordsModel.clear()
    keywordField.text = ""
    createdField.text = ""
    modifiedField.text = ""
    sourceField.text = ""
    verifiedCheck.isSelected = false
    verifiedByField.text = ""
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

  // Import helpers via old DOI/URL UI have been removed.

  private fun refreshList(selectKey: String? = null) {
    // Ensure library exists and update footer status; no list to refresh here
    val svc = project.getService(BibLibraryService::class.java)
    svc.ensureLibraryExists()
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
      abstractArea.text = e.fields["abstract"] ?: ""
      // Keywords: parse comma/semicolon-separated into list
      keywordsModel.clear()
      val kw = e.fields["keywords"]
      if (!kw.isNullOrBlank()) {
        kw.split(Regex("\\s*[,;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { keywordsModel.addElement(it) }
      }
      keywordField.text = ""
      createdField.text = e.fields["created"] ?: ""
      modifiedField.text = e.fields["modified"] ?: ""
      sourceField.text = e.fields["source"] ?: ""
      verifiedCheck.isSelected = (e.fields["verified"] ?: "false").equals("true", ignoreCase = true)
      verifiedByField.text = e.fields["verified_by"] ?: ""
      dirty = false
    } finally {
      isLoading = false
      updateStatus()
      // Re-validate after fields are populated to clear stale errors
      validateForm()
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

  private fun removeSelectedAuthors() {
    val indices = authorsList.selectedIndices
    if (indices == null || indices.isEmpty()) return
    // Remove from bottom to top to keep indices valid
    for (i in indices.sortedDescending()) {
      if (i >= 0 && i < authorsModel.size()) authorsModel.remove(i)
    }
    markDirtyFromUser()
    validateForm()
  }

  private fun removeSelectedKeywords() {
    val indices = keywordsList.selectedIndices
    if (indices == null || indices.isEmpty()) return
    for (i in indices.sortedDescending()) {
      if (i >= 0 && i < keywordsModel.size()) keywordsModel.remove(i)
    }
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

  private fun addSearchCriterion() {
    val text = searchInput.text.trim()
    if (text.isEmpty()) return
    val kind = (searchTypeCombo.selectedItem as? String) ?: "Title"
    searchCriteriaModel.addElement(SearchCriterion(kind, text))
    searchInput.text = ""
    searchInput.requestFocusInWindow()
  }

  private fun performSearchByCriteria() {
    // Include pending input as a criterion if present
    if (searchInput.text.trim().isNotEmpty()) addSearchCriterion()
    val crits = (0 until searchCriteriaModel.size()).map { searchCriteriaModel.elementAt(it) }
    if (crits.isEmpty()) {
      Messages.showErrorDialog(project, "Add at least one search criterion.", "Bibliography")
      return
    }
    val svc = project.getService(BibLibraryService::class.java)
    // Priority: DOI -> URL -> ISBN -> Title(+Author) -> Author
    fun tryImport(block: () -> BibLibraryService.BibEntry?): BibLibraryService.BibEntry? = try { block() } catch (_: Throwable) { null }
    // DOI
    for (c in crits.filter { it.kind.equals("DOI", true) }) {
      tryImport { svc.importFromAny(c.text) }?.let { onImported(it); return }
    }
    // URL
    for (c in crits.filter { it.kind.equals("URL", true) }) {
      tryImport { svc.importFromAny(c.text) }?.let { onImported(it); return }
    }
    // ISBN
    for (c in crits.filter { it.kind.equals("ISBN", true) }) {
      tryImport { svc.importFromAny(c.text) }?.let { onImported(it); return }
    }
    // Title + optional Author
    val title = crits.firstOrNull { it.kind.equals("Title", true) }?.text
    val author = crits.firstOrNull { it.kind.equals("Author", true) }?.text
    if (!title.isNullOrBlank()) {
      val q = if (!author.isNullOrBlank()) "$title $author" else title
      // Try media-targeted lookups based on selected type first
      val t = (typeField.selectedItem as? String)?.lowercase()?.trim() ?: ""
      if (t in setOf("movie/film", "video", "tv/radio broadcast")) {
        tryImport { svc.importFromAny(q) }?.let { onImported(it); return }
      } else if (t in setOf("music", "song")) {
        tryImport { svc.importFromAny(q) }?.let { onImported(it); return }
      } else {
        tryImport { svc.importFromAny(q) }?.let { onImported(it); return }
      }
    }
    // Author only
    if (!author.isNullOrBlank()) {
      tryImport { svc.importFromAny(author) }?.let { onImported(it); return }
    }

    // Optional AI fallback
    val aiEnabled = ApplicationManager.getApplication().getService(LookupSettingsService::class.java).isAiFallbackEnabled()
    if (aiEnabled && AiBibliographyLookup.isAiAvailable()) {
      val joined = crits.joinToString(" ") { it.text }
      val entry = AiBibliographyLookup.lookup(project, joined)
      if (entry != null) {
        val key = entry.key
        val augmented = entry.copy(key = key, fields = entry.fields + mapOf("source" to "automated (JetBrains AI)", "verified" to "false"))
        val saved = svc.upsertEntry(augmented)
        if (saved) { onImported(augmented); return }
      }
    }

    Messages.showErrorDialog(project, "No results found using the provided criteria.", "Bibliography")
  }

  private fun onImported(entry: BibLibraryService.BibEntry) {
    Messages.showInfoMessage(project, "Imported ${entry.key}", "Bibliography")
    loadEntryIntoForm(entry)
    dirty = false
    updateStatus()
  }

  private fun addKeywordFromField() {
    val kw = keywordField.text.trim()
    if (kw.isEmpty()) return
    keywordsModel.addElement(kw)
    keywordField.text = ""
    keywordField.requestFocusInWindow()
    markDirtyFromUser()
    validateForm()
  }

  private fun collectKeywords(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until keywordsModel.size()) list += keywordsModel.elementAt(i)
    val pending = keywordField.text.trim()
    if (pending.isNotEmpty()) list += pending
    return list.map { it.trim() }.filter { it.isNotEmpty() }
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
      "article" -> setOf("author", "title", "year", "journal", "doi", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "book" -> setOf("author", "title", "year", "publisher", "doi", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "inproceedings", "conference paper" -> setOf("author", "title", "year", "doi", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "misc" -> setOf("author", "title", "year", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "thesis (or dissertation)", "report" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "patent" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "dictionary entry" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "legislation", "regulation" -> setOf("title", "year", "publisher", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      "music", "song", "movie/film", "video", "tv/radio broadcast", "speech", "image", "website", "journal", "personal communication" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
      else -> setOf("author", "title", "year", "url", "abstract", "keywords", "source", "verified", "verified_by", "created", "modified")
    }
  }

  private fun updateVisibleFields() {
    val base = visibleKeysForSelectedType()
    val show = base +
      (if ("author" in base) setOf("author_input", "author_list") else emptySet()) +
      (if ("keywords" in base) setOf("keyword_input", "keywords_list") else emptySet())
    for ((key, row) in fieldRows) {
      val visible = key in show
      row.label.isVisible = visible
      row.field.isVisible = visible
    }
    // Update context-specific labels (e.g., Director/Artist; Studio/Label)
    val t = (typeField.selectedItem as? String)?.lowercase()?.trim() ?: ""
    val (creatorSingular, creatorPlural) = roleLabelsForType(t)
    (fieldRows["author_input"]?.label as? JLabel)?.text = creatorSingular
    (fieldRows["author_list"]?.label as? JLabel)?.text = creatorPlural
    (fieldRows["publisher"]?.label as? JLabel)?.text = publisherLabelForType(t)

    formPanel.revalidate()
    formPanel.repaint()
    if (!isLoading) validateForm()
  }

  private fun roleLabelsForType(t: String): Pair<String, String> = when (t) {
    "movie/film", "video" -> "Director" to "Directors"
    "tv/radio broadcast" -> "Director" to "Directors"
    "music", "song" -> "Artist" to "Artists"
    "speech" -> "Speaker" to "Speakers"
    else -> "Author" to "Authors"
  }

  private fun publisherLabelForType(t: String): String = when (t) {
    "movie/film", "video" -> "Studio"
    "tv/radio broadcast" -> "Network"
    "music", "song" -> "Label"
    else -> "Publisher"
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
    listOf(authorFamilyField, authorGivenField, authorsList, titleField, yearField, journalField, publisherField, doiField, urlField, abstractArea, keywordField, keywordsList, sourceField, verifiedByField, createdField, modifiedField).forEach { c ->
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

    // Source and Verified By are optional; clear any stale errors
    clearError(sourceField, "source")
    clearError(verifiedByField, "verified_by")

    return valid
  }

  private fun requiredKeysForType(t: String): Set<String> = when (t) {
    // Scholarly article requires journal and year
    "article" -> setOf("title", "author", "year", "journal")
    // Book requires publisher and year
    "book" -> setOf("title", "author", "year", "publisher")
    // Proceedings: require author, title, year
    "inproceedings", "conference paper" -> setOf("title", "author", "year")
    // Media types
    "movie/film", "video" -> setOf("title", "author", "year", "publisher")
    "music", "song" -> setOf("title", "author", "year", "publisher")
    "tv/radio broadcast" -> setOf("title", "author", "year", "publisher")
    "speech" -> setOf("title", "author", "year")
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
private fun currentUserName(): String {
  val u = System.getProperty("user.name")?.trim().orEmpty()
  return if (u.isNotEmpty()) u else "user"
}

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

    // Open detail editor on double-click
    table.addMouseListener(object : java.awt.event.MouseAdapter() {
      override fun mouseClicked(e: java.awt.event.MouseEvent) {
        if (e.clickCount == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
          val viewRow = table.rowAtPoint(e.point)
          if (viewRow >= 0) {
            val modelRow = table.convertRowIndexToModel(viewRow)
            val row = model.rows.getOrNull(modelRow) ?: return
            val full = loadEntry(row.originalType, row.key)
            if (full != null) EntryDetailDialog(project, svc, full) { refresh() }.show()
          }
        }
      }
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

  private fun loadEntry(type: String, key: String): BibLibraryService.BibEntry? =
    svc.readEntries().firstOrNull { it.type == type && it.key == key }

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
      if (row.verified != null) {
        baseFields["verified"] = if (row.verified == true) "true" else "false"
        if (row.verified == true) {
          val vb = row.verifiedBy.trim()
          baseFields["verified_by"] = if (vb.isNotEmpty()) vb else currentUserName()
        } else {
          baseFields.remove("verified_by")
        }
      }
      // Preserve source; if missing, mark as manual
      baseFields.putIfAbsent("source", "manual")
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
        9 -> {
          val v = (aValue as? Boolean) ?: false
          r.verified = v
          // Auto-manage verified_by
          r.verifiedBy = if (v) (r.verifiedBy.takeIf { !it.isNullOrBlank() } ?: currentUserName()) else ""
        }
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
    var verifiedBy: String = entry.fields["verified_by"] ?: ""
    var dirty: Boolean = false

    fun matches(q: String): Boolean {
      val hay = listOf(key, currentType, title, author, year, journal, publisher, doi, url).joinToString("\n").lowercase()
      return hay.contains(q)
    }

    fun commit() { dirty = false }
  }

  // Detail editor dialog for a single entry
  private class EntryDetailDialog(
    private val project: Project,
    private val svc: BibLibraryService,
    private var entry: BibLibraryService.BibEntry,
    private val onSaved: () -> Unit
  ) : DialogWrapper(project, true) {
    private val typeField = JComboBox(arrayOf(
      "article", "book", "inproceedings", "misc",
      "music", "movie/film", "tv/radio broadcast", "website", "journal", "speech",
      "thesis (or dissertation)", "patent", "personal communication", "dictionary entry",
      "conference paper", "image", "legislation", "video", "song", "report", "regulation"
    ))
    private val titleField = JTextField()
    private val authorField = JTextField()
    private val yearField = JTextField().apply { columns = 4 }
    private val journalField = JTextField()
    private val publisherField = JTextField()
    private val doiField = JTextField().apply { columns = 28 }
    private val urlField = JTextField()
    private val abstractArea = JTextArea().apply { lineWrap = true; wrapStyleWord = true; rows = 6 }
    private val keywordsField = JTextField()
    private val sourceField = JTextField()
    private val verifiedCheck = JCheckBox()
    private val verifiedByField = JTextField()
    private val createdField = JTextField().apply { isEditable = false; isEnabled = false }
    private val modifiedField = JTextField().apply { isEditable = false; isEnabled = false }

    private val authorLabel = JLabel("Author")
    private val publisherLabel = JLabel("Publisher")

    init {
      title = "Citation Details"
      isResizable = true
      initFieldsFrom(entry)
      // Update context labels when type changes
      (typeField as JComboBox<*>).addActionListener { updateContextLabels() }
      updateContextLabels()
      verifiedCheck.addActionListener {
        if (verifiedCheck.isSelected) {
          if (verifiedByField.text.trim().isEmpty()) verifiedByField.text = currentUserName()
        } else {
          verifiedByField.text = ""
        }
      }
      init()
    }

    private fun initFieldsFrom(e: BibLibraryService.BibEntry) {
      typeField.selectedItem = e.type
      titleField.text = e.fields["title"] ?: e.fields["booktitle"] ?: ""
      authorField.text = e.fields["author"] ?: ""
      yearField.text = (e.fields["year"] ?: "").let { s -> Regex("\\b(\\d{4})\\b").find(s)?.groupValues?.getOrNull(1) ?: "" }
      journalField.text = e.fields["journal"] ?: ""
      publisherField.text = e.fields["publisher"] ?: ""
      doiField.text = e.fields["doi"] ?: ""
      urlField.text = e.fields["url"] ?: ""
      abstractArea.text = e.fields["abstract"] ?: ""
      keywordsField.text = e.fields["keywords"] ?: ""
      sourceField.text = e.fields["source"] ?: ""
      verifiedCheck.isSelected = (e.fields["verified"] ?: "false").equals("true", true)
      verifiedByField.text = e.fields["verified_by"] ?: ""
      createdField.text = e.fields["created"] ?: ""
      modifiedField.text = e.fields["modified"] ?: ""
    }

    override fun createCenterPanel(): JComponent {
      val panel = JPanel(GridBagLayout())
      var row = 0
      fun addRow(label: String, comp: JComponent, columns: Int = 1) {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.EAST }
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = columns }
        panel.add(JLabel(label), lc)
        panel.add(comp, fc)
        row++
      }
      // Title + Year in one row
      run {
        val lcTitle = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.EAST }
        panel.add(JLabel("Title"), lcTitle)
        val fcTitle = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6) }
        panel.add(titleField, fcTitle)
        val lcYear = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.EAST }
        panel.add(JLabel("Year"), lcYear)
        val fcYear = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6) }
        panel.add(yearField, fcYear)
        row++
      }
      addRow("Type", typeField, columns = 3)
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.EAST }
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
        panel.add(authorLabel, lc)
        panel.add(authorField, fc)
        row++
      }
      addRow("Journal", journalField, columns = 3)
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.EAST }
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
        panel.add(publisherLabel, lc)
        panel.add(publisherField, fc)
        row++
      }
      addRow("DOI", doiField, columns = 3)
      addRow("URL", urlField, columns = 3)
      addRow("Abstract", JScrollPane(abstractArea).apply { preferredSize = java.awt.Dimension(200, 120) }, columns = 3)
      addRow("Keywords", keywordsField, columns = 3)

      // Metadata header
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(8,6,2,6) }
        val header = JLabel("Metadata")
        panel.add(header, lc); row++
        val sc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,6,6,6) }
        panel.add(JSeparator(), sc); row++
      }
      addRow("Source", sourceField, columns = 3)
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.EAST }
        panel.add(JLabel("Verified"), lc)
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        panel.add(verifiedCheck, fc)
        val lc2 = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.EAST }
        panel.add(JLabel("Verified By"), lc2)
        val fc2 = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
        panel.add(verifiedByField, fc2)
        row++
      }
      addRow("Created", createdField, columns = 3)
      addRow("Modified", modifiedField, columns = 3)
      return panel
    }

    private fun updateContextLabels() {
      val t = (typeField.selectedItem as? String)?.lowercase()?.trim() ?: ""
      val (creatorSingular, _) = when (t) {
        "movie/film", "video" -> "Director" to "Directors"
        "tv/radio broadcast" -> "Director" to "Directors"
        "music", "song" -> "Artist" to "Artists"
        "speech" -> "Speaker" to "Speakers"
        else -> "Author" to "Authors"
      }
      authorLabel.text = creatorSingular
      publisherLabel.text = when (t) {
        "movie/film", "video" -> "Studio"
        "tv/radio broadcast" -> "Network"
        "music", "song" -> "Label"
        else -> "Publisher"
      }
    }

    override fun doOKAction() {
      // Build fields map, preserving unknowns
      val current = svc.readEntries().associateBy { it.type + "\u0000" + it.key }
      val origType = entry.type
      val key = entry.key
      val orig = current[origType + "\u0000" + key]
      val baseFields = orig?.fields?.toMutableMap() ?: mutableMapOf()
      fun setOrRemove(k: String, v: String?) { if (v.isNullOrBlank()) baseFields.remove(k) else baseFields[k] = v }
      setOrRemove("title", titleField.text.trim())
      setOrRemove("author", authorField.text.trim())
      setOrRemove("year", yearField.text.trim())
      setOrRemove("journal", journalField.text.trim())
      setOrRemove("publisher", publisherField.text.trim())
      setOrRemove("doi", doiField.text.trim())
      setOrRemove("url", urlField.text.trim())
      setOrRemove("abstract", abstractArea.text.trim())
      setOrRemove("keywords", keywordsField.text.trim())
      // Source default to manual if empty
      val src = sourceField.text.trim()
      baseFields["source"] = if (src.isNotEmpty()) src else "manual"
      // Verified and verified_by
      if (verifiedCheck.isSelected) {
        baseFields["verified"] = "true"
        val vb = verifiedByField.text.trim()
        baseFields["verified_by"] = if (vb.isNotEmpty()) vb else currentUserName()
      } else {
        baseFields["verified"] = "false"
        baseFields.remove("verified_by")
      }

      val newType = (typeField.selectedItem as? String) ?: origType
      // If type changed, delete old entry before write
      if (newType != origType) svc.deleteEntry(origType, key)
      val saved = svc.upsertEntry(BibLibraryService.BibEntry(newType, key, baseFields))
      if (saved) {
        // Reload to update timestamps display
        svc.readEntries().firstOrNull { it.type == newType && it.key == key }?.let { updated ->
          entry = updated
          createdField.text = updated.fields["created"] ?: createdField.text
          modifiedField.text = updated.fields["modified"] ?: modifiedField.text
        }
        onSaved()
        super.doOKAction()
      } else {
        Messages.showErrorDialog(project, "Failed to save entry.", "Bibliography")
      }
    }
  }
}
