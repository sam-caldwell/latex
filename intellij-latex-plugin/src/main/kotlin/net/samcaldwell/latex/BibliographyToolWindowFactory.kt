package net.samcaldwell.latex

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
  private val typeField = JComboBox(
    arrayOf(
      // Common BibTeX types
      "article", "book", "inproceedings", "misc", "rfc",
      // Expanded types requested
      "music", "movie/film", "tv/radio broadcast", "website", "journal", "speech",
      "thesis (or dissertation)", "patent", "personal communication", "dictionary entry",
      "conference paper", "image", "legislation", "video", "song", "report", "regulation"
    ).sortedBy { it.lowercase() }.toTypedArray()
  )
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
  private val searchTypeCombo = JComboBox(arrayOf("Title", "DOI", "URL", "ISBN", "Author", "Type"))
  private val searchTypeValueCombo = JComboBox(
    arrayOf(
      "article", "book", "inproceedings", "misc", "rfc",
      "music", "movie/film", "tv/radio broadcast", "website", "journal", "speech",
      "thesis (or dissertation)", "patent", "personal communication", "dictionary entry",
      "conference paper", "image", "legislation", "video", "song", "report", "regulation"
    ).sortedBy { it.lowercase() }.toTypedArray()
  )
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
        gridwidth = 3
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
        gridx = 1; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.NONE
        anchor = GridBagConstraints.WEST
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

      // Title: display width limited to 64 characters (visual width only)
      titleField.columns = 64

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
    // DOI/ISBN + URL on the same row
    run {
      val idLabel = JLabel("DOI/ISBN")
      val urlLabel = JLabel("URL")

      val lcId = GridBagConstraints().apply {
        gridx = 0; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      val fcId = GridBagConstraints().apply {
        gridx = 1; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.NONE
        anchor = GridBagConstraints.WEST
        insets = Insets(4, 6, 4, 6)
      }
      val lcUrl = GridBagConstraints().apply {
        gridx = 2; gridy = formRow; weightx = 0.0
        fill = GridBagConstraints.NONE
        anchor = GridBagConstraints.EAST
        insets = Insets(4, 6, 4, 6)
      }
      val fcUrl = GridBagConstraints().apply {
        gridx = 3; gridy = formRow; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 0, 4, 6)
      }

      // Visual widths
      doiField.columns = 28

      formPanel.add(idLabel, lcId)
      formPanel.add(doiField, fcId)
      formPanel.add(urlLabel, lcUrl)
      formPanel.add(urlField, fcUrl)

      fieldRows["doi"] = FieldRow(idLabel, doiField)
      fieldRows["url"] = FieldRow(urlLabel, urlField)
      defaultLabelColors.putIfAbsent(idLabel, idLabel.foreground)
      defaultLabelColors.putIfAbsent(urlLabel, urlLabel.foreground)

      formRow++
    }
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
    // Metadata section removed from the main form

    val buttonPanel = JPanel()
    val addCritBtn = JButton("+")
    addCritBtn.addActionListener { addSearchCriterion() }
    val searchBtn = JButton("Search")
    searchBtn.addActionListener { performSearchByCriteria() }
    buttonPanel.add(JLabel("Search:"))
    buttonPanel.add(searchTypeCombo)
    buttonPanel.add(searchInput)
    buttonPanel.add(searchTypeValueCombo)
    buttonPanel.add(addCritBtn)
    buttonPanel.add(searchBtn)

    // Toggle between free-text input and type selector for search criteria
    (searchTypeCombo as JComboBox<*>).addActionListener {
      val kind = (searchTypeCombo.selectedItem as? String)?.lowercase() ?: ""
      val isType = kind == "type"
      searchInput.isVisible = !isType
      searchTypeValueCombo.isVisible = isType
      if (isType) {
        val current = (typeField.selectedItem as? String) ?: "article"
        searchTypeValueCombo.selectedItem = current
      }
      buttonPanel.revalidate(); buttonPanel.repaint()
    }
    // Initialize visibility
    searchTypeCombo.selectedIndex = 0
    searchInput.isVisible = true
    searchTypeValueCombo.isVisible = false

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
    // Metadata controls removed in main form

    // Track default borders for validation highlighting
    fun trackBorder(c: JComponent) { defaultBorders.putIfAbsent(c, c.border) }
    listOf(authorFamilyField, authorGivenField, titleField, yearField, journalField, publisherField, doiField, urlField, authorsList, abstractArea, keywordField, keywordsList).forEach { trackBorder(it) }
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
    if ("doi" in allowed) {
      val idVal = doiField.text.trim()
      if (idVal.isNotEmpty()) {
        val tSel = (typeField.selectedItem as? String)?.lowercase()?.trim() ?: ""
        if (tSel == "book") fields["isbn"] = idVal else fields["doi"] = idVal
      }
    }
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
    // Preserve existing metadata (source/verified/verified_by) without showing them in the form
    if (currentKey != null && currentType != null) {
      val svc = project.getService(BibLibraryService::class.java)
      val existing = svc.readEntries().firstOrNull { it.key == currentKey && it.type == currentType }
      if (existing != null) {
        existing.fields["source"]?.let { fields.putIfAbsent("source", it) }
        existing.fields["verified"]?.let { fields.putIfAbsent("verified", it) }
        existing.fields["verified_by"]?.let { fields.putIfAbsent("verified_by", it) }
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
      // Metadata fields are managed in detail dialog; not shown here
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
      // Combined DOI/ISBN display: prefer ISBN for books, DOI otherwise
      run {
        val t = e.type.lowercase()
        doiField.text = if (t == "book")
          (e.fields["isbn"] ?: e.fields["doi"] ?: "")
        else
          (e.fields["doi"] ?: e.fields["isbn"] ?: "")
      }
      urlField.text = e.fields["url"] ?: ""
      abstractArea.text = cleanupAbstractForDisplay(e.fields["abstract"]) ?: ""
      // Keywords: parse comma/semicolon-separated into list
      keywordsModel.clear()
      val kw = e.fields["keywords"]
      if (!kw.isNullOrBlank()) {
        kw.split(Regex("\\s*[,;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { keywordsModel.addElement(it) }
      }
      keywordField.text = ""
      // Metadata fields are not displayed in the main form
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

  // Normalize abstract whitespace for clean display and wrapping in main form
  private fun cleanupAbstractForDisplay(raw: String?): String? {
    if (raw == null) return null
    val s = raw.replace("\r\n", "\n").replace('\r', '\n')
    val paragraphs = s.split(Regex("\n{2,}"))
      .map { para ->
        para.replace("\n", " ")
          .replace(Regex("[\t ]+"), " ")
          .trim()
      }
    return paragraphs.filter { it.isNotEmpty() }.joinToString("\n\n")
  }

  private fun addSearchCriterion() {
    val kind = (searchTypeCombo.selectedItem as? String) ?: "Title"
    val isType = kind.equals("Type", true)
    val text = if (isType) ((searchTypeValueCombo.selectedItem as? String) ?: "").trim() else searchInput.text.trim()
    if (text.isEmpty()) return
    searchCriteriaModel.addElement(SearchCriterion(kind, text))
    if (isType) {
      // Keep selection; no text to clear
      searchTypeValueCombo.requestFocusInWindow()
    } else {
      searchInput.text = ""
      searchInput.requestFocusInWindow()
    }
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
    // Optional type override criterion
    val typeCrit = crits.lastOrNull { it.kind.equals("Type", true) }?.text?.lowercase()?.trim()
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
      val t = typeCrit ?: ((typeField.selectedItem as? String)?.lowercase()?.trim() ?: "")
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
        // Only accept AI results if all required fields for its type are present
        val req = requiredKeysForType(entry.type)
        val ok = req.all { k -> (entry.fields[k]?.trim().orEmpty()).isNotEmpty() } && (!req.contains("year") || (entry.fields["year"]?.matches(Regex("\\\\d{4}")) == true))
        if (ok) {
          val key = entry.key
          val augmented = entry.copy(key = key, fields = entry.fields + mapOf("source" to "automated (JetBrains AI)", "verified" to "false"))
          val saved = svc.upsertEntry(augmented)
          if (saved) { onImported(augmented); return }
        }
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
      "article" -> setOf("author", "title", "year", "journal", "doi", "url", "abstract", "keywords")
      "book" -> setOf("author", "title", "year", "publisher", "doi", "url", "abstract", "keywords")
      "rfc" -> setOf("author", "title", "year", "doi", "url", "abstract", "keywords")
      "inproceedings", "conference paper" -> setOf("author", "title", "year", "doi", "url", "abstract", "keywords")
      "misc" -> setOf("author", "title", "year", "url", "abstract", "keywords")
      "thesis (or dissertation)", "report" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords")
      "patent" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords")
      "dictionary entry" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords")
      "legislation", "regulation" -> setOf("title", "year", "publisher", "url", "abstract", "keywords")
      "music", "song", "movie/film", "video", "tv/radio broadcast", "speech", "image", "website", "journal", "personal communication" -> setOf("author", "title", "year", "publisher", "url", "abstract", "keywords")
      else -> setOf("author", "title", "year", "url", "abstract", "keywords")
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
    // Update DOI/ISBN label dynamically
    val idLabel = when (t) {
      "book" -> "ISBN"
      "article" -> "DOI"
      else -> "DOI/ISBN"
    }
    (fieldRows["doi"]?.label as? JLabel)?.let { (it as JLabel).text = idLabel }

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

    // DOI/ISBN format (optional): accept DOI (10.x/...) or ISBN-10/13
    val idVal = doiField.text.trim()
    if (idVal.isNotEmpty()) {
      val doiOk = idVal.matches(Regex("(?i)^10\\.\\S+/.+"))
      val isbnRaw = idVal.uppercase().replace("[^0-9X]".toRegex(), "")
      val isbnOk = isbnRaw.length == 10 || isbnRaw.length == 13
      if (!doiOk && !isbnOk) { flagError("doi", doiField, "Invalid DOI or ISBN"); valid = false } else clearError(doiField, "doi")
    } else clearError(doiField, "doi")

    // URL format
    val urlVal = urlField.text.trim()
    val urlOk = if (urlVal.isEmpty()) true else try {
      (urlVal.startsWith("http://") || urlVal.startsWith("https://")) && java.net.URI(urlVal).isAbsolute
    } catch (_: Throwable) { false }
    if (!urlOk) { flagError("url", urlField, "URL must start with http:// or https://") ; valid = false } else clearError(urlField, "url")

    // No metadata fields on the main form

    return valid
  }

  private fun requiredKeysForType(t: String): Set<String> = when (t) {
    // Scholarly article requires journal and year
    "article" -> setOf("title", "author", "year", "journal")
    // Book requires publisher and year
    "book" -> setOf("title", "author", "year", "publisher")
    // RFC requires author, title, year (identifier handled via DOI/URL)
    "rfc" -> setOf("title", "author", "year")
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
    private val typeField = JComboBox(
      arrayOf(
        "article", "book", "inproceedings", "misc", "rfc",
        "music", "movie/film", "tv/radio broadcast", "website", "journal", "speech",
        "thesis (or dissertation)", "patent", "personal communication", "dictionary entry",
        "conference paper", "image", "legislation", "video", "song", "report", "regulation"
      ).sortedBy { it.lowercase() }.toTypedArray()
    )
    private val titleField = JTextField()
    // Structured author editor (similar to main form)
    private data class AuthorName(val family: String, val given: String)
    private val authorFamilyField = JTextField()
    private val authorGivenField = JTextField()
    private val authorsModel = DefaultListModel<AuthorName>()
    private val authorsList = JList(authorsModel).apply {
      visibleRowCount = 6
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
    private val yearField = JTextField().apply { columns = 4 }
    private val keyField = JTextField().apply { columns = 20 }
    private val journalField = JTextField()
    private val publisherField = JTextField()
    private val doiField = JTextField().apply { columns = 28 }
    private val urlField = JTextField()
    private val abstractArea = JTextArea().apply { lineWrap = true; wrapStyleWord = true; rows = 6 }
    // Structured keywords (similar to main form)
    private val keywordInputField = JTextField()
    private val keywordsModel = DefaultListModel<String>()
    private val keywordsList = JList(keywordsModel).apply {
      visibleRowCount = 6
      selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    }
    private val sourceField = JTextField()
    private val verifiedCheck = JCheckBox()
    private val verifiedByField = JTextField()
    private val createdField = JTextField().apply { isEditable = false; isEnabled = false }
    private val modifiedField = JTextField().apply { isEditable = false; isEnabled = false }

    private val authorLabel = JLabel("Author")
    private val authorsListLabel = JLabel("Authors")
    private val publisherLabel = JLabel("Publisher")
    private val idLabel = JLabel("DOI/ISBN")
    private val corporateCheck = JCheckBox("Corporate Author")
    private val corporateNameField = JTextField()
    private lateinit var authorModeCards: JPanel
    private lateinit var authorModeLayout: java.awt.CardLayout
    private lateinit var authorsScrollRef: JScrollPane

    init {
      title = "Citation Details"
      isResizable = false
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
      keyField.text = e.key
      // Populate authors or corporate name
      authorsModel.clear()
      val rawAuthor = (e.fields["author"] ?: "").trim()
      val isCorp = rawAuthor.isNotEmpty() && !rawAuthor.contains(" and ", ignoreCase = true) && !rawAuthor.contains(',')
      corporateCheck.isSelected = isCorp
      if (isCorp) {
        corporateNameField.text = rawAuthor
      } else {
        parseAuthors(rawAuthor).forEach { authorsModel.addElement(it) }
        authorFamilyField.text = ""
        authorGivenField.text = ""
      }
      yearField.text = (e.fields["year"] ?: "").let { s -> Regex("\\b(\\d{4})\\b").find(s)?.groupValues?.getOrNull(1) ?: "" }
      journalField.text = e.fields["journal"] ?: ""
      publisherField.text = e.fields["publisher"] ?: ""
      // Combined DOI/ISBN field: prefer ISBN for books, DOI otherwise
      run {
        val t = e.type.lowercase()
        doiField.text = if (t == "book") (e.fields["isbn"] ?: e.fields["doi"] ?: "") else (e.fields["doi"] ?: e.fields["isbn"] ?: "")
      }
      urlField.text = e.fields["url"] ?: ""
      abstractArea.text = e.fields["abstract"] ?: ""
      // Populate structured keywords
      keywordsModel.clear()
      (e.fields["keywords"] ?: "").let { s ->
        if (s.isNotBlank()) s.split(Regex("\\s*[,;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { keywordsModel.addElement(it) }
      }
      keywordInputField.text = ""
      sourceField.text = sanitizeSource(e.fields["source"]) ?: ""
      verifiedCheck.isSelected = (e.fields["verified"] ?: "false").equals("true", true)
      verifiedByField.text = e.fields["verified_by"] ?: ""
      createdField.text = e.fields["created"] ?: ""
      modifiedField.text = e.fields["modified"] ?: ""
    }

    override fun createCenterPanel(): JComponent {
      val panel = JPanel(GridBagLayout())
      var row = 0
      fun addRow(label: String, comp: JComponent, columns: Int = 1) {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = columns }
        panel.add(JLabel(label), lc)
        panel.add(comp, fc)
        row++
      }
      // Title + Year + Key in one row
      run {
        val lcTitle = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("Title"), lcTitle)
        val fcTitle = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6) }
        panel.add(titleField, fcTitle)
        val lcYear = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("Year"), lcYear)
        val fcYear = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6) }
        panel.add(yearField, fcYear)
        val lcKey = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("Key"), lcKey)
        val fcKey = GridBagConstraints().apply { gridx = 5; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
        panel.add(keyField, fcKey)
        row++
      }
      addRow("Type", typeField, columns = 3)
      // Author input row: person vs corporate
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        panel.add(authorLabel, lc)
        val personPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
        authorFamilyField.columns = 12
        authorGivenField.columns = 12
        val addBtn = JButton("+")
        addBtn.addActionListener { addAuthorFromFields() }
        personPanel.add(JLabel("Family:"))
        personPanel.add(authorFamilyField)
        personPanel.add(JLabel("Given:"))
        personPanel.add(authorGivenField)
        personPanel.add(addBtn)
        val corpPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
          add(JLabel("Corporate Name"))
          corporateNameField.columns = 24
          add(corporateNameField)
        }
        authorModeLayout = java.awt.CardLayout()
        authorModeCards = JPanel(authorModeLayout).apply {
          add(personPanel, "person")
          add(corpPanel, "corp")
        }
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
        panel.add(authorModeCards, fc)
        val lcToggle = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
        panel.add(corporateCheck, lcToggle)
        row++
      }
      // Authors list row with context delete and double-click edit
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
        panel.add(authorsListLabel, lc)
        val scroll = JScrollPane(authorsList).apply { preferredSize = java.awt.Dimension(200, 120) }
        authorsScrollRef = scroll
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(4,6,4,6); gridwidth = 3 }
        panel.add(scroll, fc)
        row++
        // Context menu delete
        val popup = JPopupMenu().apply {
          val del = JMenuItem("Delete"); del.addActionListener { removeSelectedAuthors() }; add(del)
        }
        fun showPopup(e: java.awt.event.MouseEvent) {
          val idx = authorsList.locationToIndex(e.point)
          if (idx >= 0) {
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
          override fun mouseClicked(e: java.awt.event.MouseEvent) {
            if (e.clickCount == 2) editSelectedAuthor()
          }
        })
      }
      addRow("Journal", journalField, columns = 3)
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
        panel.add(publisherLabel, lc)
        panel.add(publisherField, fc)
        row++
      }
      // DOI/ISBN + URL on the same row
      run {
        val lcId = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        panel.add(idLabel, lcId)
        val fcId = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.NONE; weightx = 0.0 }
        panel.add(doiField, fcId)
        val lcUrl = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("URL"), lcUrl)
        val fcUrl = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
        panel.add(urlField, fcUrl)
        row++
      }
      addRow("Abstract", JScrollPane(abstractArea).apply { preferredSize = java.awt.Dimension(200, 120) }, columns = 3)
      // Keyword input + list (structured)
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("Keywords"), lc)
        val input = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
        keywordInputField.columns = 18
        val addBtn = JButton("+")
        addBtn.addActionListener { addKeywordFromField() }
        input.add(keywordInputField)
        input.add(addBtn)
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
        panel.add(input, fc)
        row++
      }
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
        panel.add(JLabel("Keyword List"), lc)
        val scroll = JScrollPane(keywordsList).apply { preferredSize = java.awt.Dimension(200, 100) }
        val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(4,6,4,6); gridwidth = 3 }
        panel.add(scroll, fc)
        row++
        // Context menu delete and double-click edit
        val popup = JPopupMenu().apply {
          val del = JMenuItem("Delete"); del.addActionListener { removeSelectedKeywords() }; add(del)
        }
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
          override fun mouseClicked(e: java.awt.event.MouseEvent) {
            if (e.clickCount == 2) editSelectedKeyword()
          }
        })
      }

      // Metadata header
      run {
        val lc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(8,6,2,6) }
        val header = JLabel("Metadata")
        panel.add(header, lc); row++
        val sc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,6,6,6) }
        panel.add(JSeparator(), sc); row++
      }
      // Source (20 chars) + Verified (checkbox) + Verified By on the same row
      run {
        val lcSrc = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("Source"), lcSrc)
        sourceField.columns = 20
        val fcSrc = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.NONE; weightx = 0.0 }
        panel.add(sourceField, fcSrc)

        // Verified checkbox + Verified By input in a single subpanel spanning columns 2..3
        val verifyPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
          if (verifiedCheck.text.isNullOrBlank()) verifiedCheck.text = "Verified"
          add(verifiedCheck)
          add(JLabel("Verified By"))
          add(verifiedByField)
        }
        val fcVerify = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; gridwidth = 2 }
        panel.add(verifyPanel, fcVerify)
        row++
      }
      // Created + Modified on the same row (each ~50%)
      run {
        val lcCreated = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("Created"), lcCreated)
        val fcCreated = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 0.5 }
        panel.add(createdField, fcCreated)
        val lcModified = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
        panel.add(JLabel("Modified"), lcModified)
        val fcModified = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 0.5 }
        panel.add(modifiedField, fcModified)
        row++
      }
      // Wire up corporate toggle and set initial mode
      corporateCheck.addActionListener { updateAuthorModeVisibility() }
      updateAuthorModeVisibility()
      // Fix dialog size (width x height)
      val fixed = java.awt.Dimension(900, 620)
      panel.preferredSize = fixed
      panel.minimumSize = fixed
      panel.maximumSize = fixed
      return panel
    }

    private fun updateAuthorModeVisibility() {
      val corp = corporateCheck.isSelected
      if (corp) authorModeLayout.show(authorModeCards, "corp") else authorModeLayout.show(authorModeCards, "person")
      authorsListLabel.isVisible = !corp
      authorsScrollRef.isVisible = !corp
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
      authorsListLabel.text = when (t) {
        "movie/film", "video", "tv/radio broadcast" -> "Directors"
        "music", "song" -> "Artists"
        "speech" -> "Speakers"
        else -> "Authors"
      }
      publisherLabel.text = when (t) {
        "movie/film", "video" -> "Studio"
        "tv/radio broadcast" -> "Network"
        "music", "song" -> "Label"
        else -> "Publisher"
      }
      idLabel.text = when (t) {
        "book" -> "ISBN"
        "article" -> "DOI"
        else -> "DOI/ISBN"
      }
    }

    override fun doOKAction() {
      // Build fields map, preserving unknowns
      val current = svc.readEntries().associateBy { it.type + "\u0000" + it.key }
      val origType = entry.type
      val origKey = entry.key
      val orig = current[origType + "\u0000" + origKey]
      val baseFields = orig?.fields?.toMutableMap() ?: mutableMapOf()
      fun setOrRemove(k: String, v: String?) { if (v.isNullOrBlank()) baseFields.remove(k) else baseFields[k] = v }
      setOrRemove("title", titleField.text.trim())
      // Build authors or corporate author
      if (corporateCheck.isSelected) {
        val corp = corporateNameField.text.trim()
        setOrRemove("author", corp)
      } else {
        collectAuthors().takeIf { it.isNotEmpty() }?.let { list ->
          val value = list.joinToString(" and ") { n -> if (n.given.isNotBlank()) "${n.family}, ${n.given}" else n.family }
          setOrRemove("author", value)
        } ?: run { baseFields.remove("author") }
      }
      setOrRemove("year", yearField.text.trim())
      setOrRemove("journal", journalField.text.trim())
      setOrRemove("publisher", publisherField.text.trim())
      // Save combined DOI/ISBN to the appropriate field
      val tSel = (typeField.selectedItem as? String)?.toString()?.lowercase()?.trim() ?: origType
      val idVal = doiField.text.trim()
      if (idVal.isEmpty()) {
        baseFields.remove("doi")
        baseFields.remove("isbn")
      } else {
        if (tSel == "book") {
          baseFields.remove("doi")
          baseFields["isbn"] = idVal
        } else {
          baseFields.remove("isbn")
          baseFields["doi"] = idVal
        }
      }
      setOrRemove("url", urlField.text.trim())
      setOrRemove("abstract", abstractArea.text.trim())
      // Structured keywords save
      run {
        val list = collectKeywords()
        if (list.isNotEmpty()) baseFields["keywords"] = list.joinToString(", ") else baseFields.remove("keywords")
      }
      // Source default to manual if empty
      val src = sourceField.text.trim()
      val srcNorm = sanitizeSource(src)
      baseFields["source"] = if (!srcNorm.isNullOrEmpty()) srcNorm else "manual"
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
      val newKey = keyField.text.trim().ifEmpty { origKey }
      // If type or key changed, delete old entry before write
      if (newType != origType || newKey != origKey) svc.deleteEntry(origType, origKey)
      val saved = svc.upsertEntry(BibLibraryService.BibEntry(newType, newKey, baseFields))
      if (saved) {
        // Reload to update timestamps display
        svc.readEntries().firstOrNull { it.type == newType && it.key == newKey }?.let { updated ->
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

    // --- Structured keywords helpers ---
    private fun addKeywordFromField() {
      val kw = keywordInputField.text.trim()
      if (kw.isEmpty()) return
      keywordsModel.addElement(kw)
      keywordInputField.text = ""
      keywordInputField.requestFocusInWindow()
    }
    private fun sanitizeSource(raw: String?): String? {
      if (raw == null) return null
      val v = raw.trim()
      return if (v.equals("head/get", ignoreCase = true)) "http" else v
    }
    private fun removeSelectedKeywords() {
      val idxs = keywordsList.selectedIndices
      if (idxs == null || idxs.isEmpty()) return
      for (i in idxs.sortedDescending()) {
        if (i >= 0 && i < keywordsModel.size()) keywordsModel.remove(i)
      }
    }
    private fun editSelectedKeyword() {
      val i = keywordsList.selectedIndex
      if (i < 0) return
      val v = keywordsModel.get(i)
      keywordsModel.remove(i)
      keywordInputField.text = v
      keywordInputField.requestFocusInWindow()
    }
    private fun collectKeywords(): List<String> {
      val list = mutableListOf<String>()
      for (i in 0 until keywordsModel.size()) list += keywordsModel.elementAt(i)
      val pending = keywordInputField.text.trim()
      if (pending.isNotEmpty()) list += pending
      return list.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // --- Structured authors helpers ---
    private fun addAuthorFromFields() {
      val fam = authorFamilyField.text.trim()
      val giv = authorGivenField.text.trim()
      if (fam.isEmpty() && giv.isEmpty()) return
      authorsModel.addElement(AuthorName(fam, giv))
      authorFamilyField.text = ""
      authorGivenField.text = ""
      authorFamilyField.requestFocusInWindow()
    }
    private fun removeSelectedAuthors() {
      val idxs = authorsList.selectedIndices
      if (idxs == null || idxs.isEmpty()) return
      for (i in idxs.sortedDescending()) {
        if (i >= 0 && i < authorsModel.size()) authorsModel.remove(i)
      }
    }
    private fun editSelectedAuthor() {
      val i = authorsList.selectedIndex
      if (i < 0) return
      val n = authorsModel.get(i)
      authorsModel.remove(i)
      authorFamilyField.text = n.family
      authorGivenField.text = n.given
      authorFamilyField.requestFocusInWindow()
    }
    private fun parseAuthors(s: String): List<AuthorName> {
      val parts = s.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
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
    private fun collectAuthors(): List<AuthorName> {
      val list = mutableListOf<AuthorName>()
      for (i in 0 until authorsModel.size()) list += authorsModel.elementAt(i)
      val fam = authorFamilyField.text.trim(); val giv = authorGivenField.text.trim()
      if (fam.isNotEmpty() || giv.isNotEmpty()) list += AuthorName(fam, giv)
      return list.filter { it.family.isNotBlank() || it.given.isNotBlank() }
    }

    // Normalize abstract whitespace for clean display and wrapping
    private fun cleanupAbstract(raw: String?): String? {
      if (raw == null) return null
      val s = raw.replace("\r\n", "\n").replace('\r', '\n')
      // Preserve paragraph breaks: 2+ newlines -> exactly two newlines
      // Single newlines and excess spaces collapse to single spaces
      val paragraphs = s.split(Regex("\n{2,}"))
        .map { para ->
          para.replace("\n", " ")
            .replace(Regex("[\t ]+"), " ")
            .trim()
        }
      return paragraphs.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
  }
}
