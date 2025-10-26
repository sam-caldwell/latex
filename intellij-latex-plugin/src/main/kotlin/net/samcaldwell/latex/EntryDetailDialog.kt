package net.samcaldwell.latex

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import java.awt.Color
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import javax.swing.BorderFactory

class EntryDetailDialog(
  private val project: Project,
  private val svc: BibLibraryService,
  private var entry: BibLibraryService.BibEntry,
  private val onSaved: () -> Unit
) : DialogWrapper(project, true) {
  private val typeField = JComboBox(CitationTypes.array())
  private val titleField = JTextField()
  // Authors editor: unified list; items are display strings.
  private data class AuthorName(val family: String, val given: String)
  private val authorFamilyField = JTextField()
  private val authorGivenField = JTextField()
  private val authorSingleField = JTextField()
  private val authorsModel = DefaultListModel<String>()
  private val authorsList = JList(authorsModel).apply {
    visibleRowCount = 6
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
  }
  private val yearField = JTextField().apply { columns = 4 }
  private val dateField = JTextField()
  private val dateLabel = JLabel("Date")
  private val keyField = JTextField().apply {
    columns = 20
    isEditable = false
    toolTipText = "Key is read-only"
  }
  private val journalField = JTextField()
  private val publisherField = JTextField()
  private val volumeField = JTextField().apply { columns = 4 }
  private val issueField = JTextField().apply { columns = 4 }
  private val pagesField = JTextField().apply { columns = 8 }
  private val doiField = JTextField().apply { columns = 21 }
  private val lookupBtn = JButton("Lookup").apply {
    toolTipText = "Lookup metadata by DOI/ISBN (OpenLibrary → Google Books → Crossref → WorldCat → BNB → openBD → LOC)"
  }
  private val urlField = JTextField()
  private val abstractArea = JTextArea().apply { lineWrap = true; wrapStyleWord = true; rows = 6 }
  // Court case specific fields
  private val reporterVolumeField = JTextField().apply { columns = 6 }
  private val reporterAbbrevField = JTextField().apply { columns = 10 }
  private val firstPageField = JTextField().apply { columns = 6 }
  private val pinpointField = JTextField().apply { columns = 6 }
  private val docketNumberField = JTextField().apply { columns = 12 }
  private val wlCiteField = JTextField().apply { columns = 14 }
  // Structured keywords (similar to main form)
  private val keywordInputField = JTextField()
  private val keywordsModel = DefaultListModel<String>()
  private val keywordsList = JList(keywordsModel).apply {
    visibleRowCount = 6
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
  }
  private fun sortKeywordsModel() {
    val items = mutableListOf<String>()
    for (i in 0 until keywordsModel.size()) items += keywordsModel.getElementAt(i)
    items.sortWith(compareBy<String> { it.lowercase() }.thenBy { it })
    keywordsModel.clear()
    items.forEach { keywordsModel.addElement(it) }
  }
  private val sourceField = JTextField()
  private val verifiedCheck = JCheckBox()
  private val verifiedByField = JTextField()
  private val createdField = JTextField().apply { isEditable = false }
  private val modifiedField = JTextField().apply { isEditable = false }

  private val authorLabel = JLabel("Author")
  private val authorsListLabel = JLabel("Authors")
  private val publisherLabel = JLabel("Publisher")
  private val journalLabel = JLabel("Journal")
  private val volumeLabel = JLabel("vol.")
  private val issueLabel = JLabel("iss.")
  private val pagesLabel = JLabel("pgs.")
  private val idLabel = JLabel("DOI/ISBN")
  private val reporterVolumeLabel = JLabel("Volume")
  private val reporterAbbrevLabel = JLabel("Reporter")
  private val firstPageLabel = JLabel("First page")
  private val pinpointLabel = JLabel("Pinpoint")
  private val docketNumberLabel = JLabel("Docket No.")
  private val wlCiteLabel = JLabel("WL/Lexis")
  private val corporateCheck = JCheckBox("Corporate Author")
  // Input mode toggle and container
  private lateinit var authorModeCards: JPanel
  private lateinit var authorModeLayout: java.awt.CardLayout
  private lateinit var authorsScrollRef: JScrollPane
  // Citation preview controls
  private val formatCombo = JComboBox(arrayOf("APA 7", "MLA", "Chicago", "IEEE"))
  private val copyCitationBtn = JButton("Copy Citation")
  private val previewArea = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 3 }
  private val defaultBorders = mutableMapOf<JComponent, javax.swing.border.Border?>()
  private fun trackBorder(c: JComponent) { defaultBorders.putIfAbsent(c, c.border) }

  init {
    title = "Citation Details"
    isResizable = false
    // Style read-only fields with grey text
    run {
      val grey = try {
        javax.swing.UIManager.getColor("TextField.inactiveForeground")
          ?: javax.swing.UIManager.getColor("Label.disabledForeground")
          ?: Color(0x6b6b6b)
      } catch (_: Throwable) { Color(0x6b6b6b) }
      keyField.foreground = grey
      createdField.foreground = grey
      modifiedField.foreground = grey
    }
    initFieldsFrom(entry)
    // Hook type changes; defer initial update until UI is built
    (typeField as JComboBox<*>).addActionListener { updateContextLabels() }
    verifiedCheck.addActionListener {
      if (verifiedCheck.isSelected) {
        if (verifiedByField.text.trim().isEmpty()) verifiedByField.text = currentUserName()
      } else {
        verifiedByField.text = ""
      }
    }
    // Wire edits to preview
    fun JTextField.onUserChange() {
      this.document.addDocumentListener(object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
      })
    }
    listOf(titleField, yearField, dateField, journalField, volumeField, issueField, pagesField, publisherField, doiField, urlField, keywordInputField, verifiedByField, sourceField).forEach { it.onUserChange() }
    abstractArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
    })
    (typeField as JComboBox<*>).addActionListener { updateCitationPreview() }
    corporateCheck.addActionListener { updateCitationPreview() }
    formatCombo.addActionListener { updateCitationPreview() }
    copyCitationBtn.addActionListener { copyCurrentCitation() }
    lookupBtn.addActionListener { performLookup() }
    init()
  }

  private fun performLookup() {
    val raw = doiField.text.trim()
    if (raw.isEmpty()) {
      Messages.showInfoMessage(project, "Enter a DOI or ISBN to look up.", "Lookup")
      return
    }
    val tSel = (typeField.selectedItem as? String)?.toString()?.trim()?.lowercase() ?: entry.type
    val isIsbnMode = tSel == "book" || isValidIsbn(raw)
    val titleBefore = titleField.text
    lookupBtn.isEnabled = false
    object : com.intellij.openapi.progress.Task.Backgroundable(project, "Looking up metadata", true) {
      override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
        indicator.isIndeterminate = true
        val res = try {
          if (isIsbnMode) svc.lookupByIsbnCascade(raw) else svc.lookupEntryByDoiOrUrl(raw)
        } catch (t: Throwable) { null }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
          lookupBtn.isEnabled = true
          if (res == null) {
            Messages.showWarningDialog(project, "No metadata found for '${raw}'.", "Lookup")
            return@invokeLater
          }
          // Merge fetched fields into current form; prefer fetched values
          val mergedFields = entry.fields.toMutableMap().apply { putAll(res.fields) }
          val viewEntry = BibLibraryService.BibEntry(
            (typeField.selectedItem as? String)?.toString()?.trim()?.lowercase() ?: res.type,
            keyField.text.ifBlank { res.key },
            mergedFields
          )
          initFieldsFrom(viewEntry)
          // Keep the chosen type selection unchanged
          typeField.selectedItem = tSel
          updateContextLabels()
          if (titleBefore.isBlank() && !viewEntry.fields["title"].isNullOrBlank()) titleField.caretPosition = 0
        }
      }
    }.queue()
  }

  private fun initFieldsFrom(e: BibLibraryService.BibEntry) {
    typeField.selectedItem = e.type
    titleField.text = e.fields["title"] ?: e.fields["booktitle"] ?: ""
    keyField.text = e.key
    // Populate authors list (unified). Detect corporate vs personal based on comma presence
    authorsModel.clear()
    val rawAuthor = (e.fields["author"] ?: "").trim()
    val parts = if (rawAuthor.isNotEmpty()) rawAuthor.split(Regex("""\s+and\s+""", RegexOption.IGNORE_CASE)).map { it.trim().trim('{','}') }.filter { it.isNotEmpty() } else emptyList()
    val isCorp = parts.isNotEmpty() && parts.all { !it.contains(',') }
    corporateCheck.isSelected = isCorp
    if (isCorp) {
      parts.forEach { authorsModel.addElement(it) }
      authorSingleField.text = ""
    } else {
      parseAuthors(rawAuthor).forEach { n ->
        val s = if (n.given.isNotBlank()) "${n.family}, ${n.given}" else n.family
        authorsModel.addElement(s)
      }
      authorFamilyField.text = ""
      authorGivenField.text = ""
    }
    yearField.text = (e.fields["year"] ?: "").let { s -> Regex("""\b(\d{4})\b""").find(s)?.groupValues?.getOrNull(1) ?: "" }
    dateField.text = e.fields["date"] ?: ""
    journalField.text = e.fields["journal"] ?: ""
    volumeField.text = e.fields["volume"] ?: ""
    issueField.text = (e.fields["number"] ?: e.fields["issue"]) ?: ""
    pagesField.text = e.fields["pages"] ?: ""
    publisherField.text = e.fields["publisher"] ?: ""
    // Court case fields
    reporterVolumeField.text = e.fields["reporter_volume"] ?: ""
    reporterAbbrevField.text = e.fields["reporter"] ?: ""
    firstPageField.text = e.fields["first_page"] ?: ""
    pinpointField.text = e.fields["pinpoint"] ?: ""
    docketNumberField.text = e.fields["docket"] ?: ""
    wlCiteField.text = e.fields["wl"] ?: ""
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
      if (s.isNotBlank()) s.split(Regex("""\s*[,;]\s*""")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { keywordsModel.addElement(it) }
    }
    sortKeywordsModel()
    keywordInputField.text = ""
    sourceField.text = sanitizeSource(e.fields["source"]) ?: ""
    verifiedCheck.isSelected = (e.fields["verified"] ?: "false").equals("true", true)
    verifiedByField.text = e.fields["verified_by"] ?: ""
    createdField.text = e.fields["created"] ?: ""
    modifiedField.text = e.fields["modified"] ?: ""
  }

  override fun createCenterPanel(): JComponent {
    val form = JPanel(GridBagLayout())
    var row = 0
    fun addRow(label: String, comp: JComponent, columns: Int = 1) {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = columns }
      form.add(JLabel(label), lc)
      form.add(comp, fc)
      row++
    }
    // Title + Year + Key
    run {
      val lcTitle = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Title"), lcTitle)
      val fcTitle = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6) }
      form.add(titleField, fcTitle)
      val lcYear = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Year"), lcYear)
      val fcYear = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6) }
      form.add(yearField, fcYear)
      val lcKey = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Key"), lcKey)
      val fcKey = GridBagConstraints().apply { gridx = 5; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      form.add(keyField, fcKey)
      row++
    }
    addRow("Type", typeField, columns = 3)
    // Date row
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(dateLabel, lc)
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; gridwidth = 3 }
      form.add(dateField, fc)
      row++
    }
    // Author input row: person vs corporate
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(authorLabel, lc)
      val personPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
      authorFamilyField.columns = 12
      authorGivenField.columns = 12
      val addBtn = JButton("+")
      addBtn.addActionListener { addAuthorFromInput() }
      personPanel.add(JLabel("Family:"))
      personPanel.add(authorFamilyField)
      personPanel.add(JLabel("Given:"))
      personPanel.add(authorGivenField)
      personPanel.add(addBtn)
      val corpPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
        add(JLabel("Author"))
        authorSingleField.columns = 24
        add(authorSingleField)
        val addBtn2 = JButton("+")
        addBtn2.addActionListener { addAuthorFromInput() }
        add(addBtn2)
      }
      authorModeLayout = java.awt.CardLayout()
      authorModeCards = JPanel(authorModeLayout).apply {
        add(personPanel, "person")
        add(corpPanel, "corp")
      }
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
      form.add(authorModeCards, fc)
      val lcToggle = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(corporateCheck, lcToggle)
      row++
    }
    // Authors list + reordering
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
      form.add(authorsListLabel, lc)
      val scroll = JScrollPane(authorsList).apply { preferredSize = java.awt.Dimension(200, 120) }
      authorsScrollRef = scroll
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(4,6,4,6); gridwidth = 3 }
      form.add(scroll, fc)
      val buttons = JPanel(java.awt.GridLayout(2,1,4,4)).apply {
        val up = JButton(AllIcons.Actions.MoveUp); up.toolTipText = "Move up"; up.addActionListener { moveAuthorsUp() }; add(up)
        val dn = JButton(AllIcons.Actions.MoveDown); dn.toolTipText = "Move down"; dn.addActionListener { moveAuthorsDown() }; add(dn)
      }
      val bc = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
      form.add(buttons, bc)
      row++
      val popup = JPopupMenu().apply { val del = JMenuItem("Delete"); del.addActionListener { removeSelectedAuthors() }; add(del) }
      fun showPopup(e: java.awt.event.MouseEvent) {
        val idx = authorsList.locationToIndex(e.point)
        if (idx >= 0) {
          val sel = authorsList.selectedIndices.toSet()
          if (idx !in sel) authorsList.selectedIndex = idx
        }
        popup.show(authorsList, e.x, e.y)
      }
      authorsList.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e)) showPopup(e) }
        override fun mouseReleased(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) showPopup(e) }
        override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) editSelectedAuthor() }
      })
    }
    // Corporate authors dedicated section removed; unified authors list above is used
    // Court case: reporter info
    run {
      val lcRv = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(reporterVolumeLabel, lcRv)
      val fcRv = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(reporterVolumeField, fcRv)
      val lcRa = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(reporterAbbrevLabel, lcRa)
      val fcRa = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
      form.add(reporterAbbrevField, fcRa)
      row++
      val lcFp = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(firstPageLabel, lcFp)
      val fcFp = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(firstPageField, fcFp)
      val lcPin = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(pinpointLabel, lcPin)
      val fcPin = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      form.add(pinpointField, fcPin)
      row++
      val lcDock = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(docketNumberLabel, lcDock)
      val fcDock = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(docketNumberField, fcDock)
      val lcWl = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(wlCiteLabel, lcWl)
      val fcWl = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      form.add(wlCiteField, fcWl)
      row++
    }
    // Journal + (vol., iss., pgs.) + Publisher on the same row
    run {
      val lcJournal = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(journalLabel, lcJournal)
      val fcJournal = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 0.4 }
      form.add(journalField, fcJournal)

      val lcVol = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(volumeLabel, lcVol)
      val fcVol = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      form.add(volumeField, fcVol)

      val lcIss = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(issueLabel, lcIss)
      val fcIss = GridBagConstraints().apply { gridx = 5; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      form.add(issueField, fcIss)

      val lcPgs = GridBagConstraints().apply { gridx = 6; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(pagesLabel, lcPgs)
      val fcPgs = GridBagConstraints().apply { gridx = 7; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      form.add(pagesField, fcPgs)

      val lcPublisher = GridBagConstraints().apply { gridx = 8; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(publisherLabel, lcPublisher)
      val fcPublisher = GridBagConstraints().apply { gridx = 9; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 0.6 }
      form.add(publisherField, fcPublisher)
      row++
    }
    // DOI/ISBN + URL row
    run {
      val lcId = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(idLabel, lcId)
      val fcId = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.NONE; weightx = 0.0 }
      form.add(doiField, fcId)
      val fcLookup = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      form.add(lookupBtn, fcLookup)
      val lcUrl = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("URL"), lcUrl)
      val fcUrl = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
      form.add(urlField, fcUrl)
      row++
    }
    addRow("Abstract", JScrollPane(abstractArea).apply { preferredSize = java.awt.Dimension(200, 120) }, columns = 3)
    // Keyword input + list
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Keywords"), lc)
      val input = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
      keywordInputField.columns = 18
      val addBtn = JButton("+")
      addBtn.addActionListener { addKeywordFromField() }
      input.add(keywordInputField)
      input.add(addBtn)
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
      form.add(input, fc)
      row++
    }
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
      form.add(JLabel("Keyword List"), lc)
      val scroll = JScrollPane(keywordsList).apply { preferredSize = java.awt.Dimension(200, 100) }
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(4,6,4,6); gridwidth = 3 }
      form.add(scroll, fc)
      row++
      val popup = JPopupMenu().apply { val del = JMenuItem("Delete"); del.addActionListener { removeSelectedKeywords() }; add(del) }
      fun showPopup(e: java.awt.event.MouseEvent) {
        val idx = keywordsList.locationToIndex(e.point)
        if (idx >= 0) {
          val sel = keywordsList.selectedIndices.toSet()
          if (idx !in sel) keywordsList.selectedIndex = idx
        }
        popup.show(keywordsList, e.x, e.y)
      }
      keywordsList.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e)) showPopup(e) }
        override fun mouseReleased(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) showPopup(e) }
        override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) editSelectedKeyword() }
      })
    }

    // Metadata header
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(8,6,2,6) }
      val header = JLabel("Metadata")
      form.add(header, lc); row++
      val sc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,6,6,6) }
      form.add(JSeparator(), sc); row++
    }
    // Source + Verified + Verified By
    run {
      val lcSrc = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Source"), lcSrc)
      sourceField.columns = 20
      val fcSrc = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.NONE; weightx = 0.0 }
      form.add(sourceField, fcSrc)

      val verifyPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
        if (verifiedCheck.text.isNullOrBlank()) verifiedCheck.text = "Verified"
        add(verifiedCheck)
        add(JLabel("Verified By"))
        add(verifiedByField)
      }
      val fcVerify = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; gridwidth = 2 }
      form.add(verifyPanel, fcVerify)
      row++
    }
    // Created + Modified
    run {
      val lcCreated = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Created"), lcCreated)
      val fcCreated = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 0.5 }
      form.add(createdField, fcCreated)
      val lcModified = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Modified"), lcModified)
      val fcModified = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 0.5 }
      form.add(modifiedField, fcModified)
      row++
    }
    // Toggle authors mode, border tracking, preview controls
    corporateCheck.addActionListener { updateAuthorModeVisibility() }
    updateAuthorModeVisibility()
    listOf(titleField, yearField, dateField, publisherField, doiField, urlField, authorsList,
      reporterVolumeField, reporterAbbrevField, firstPageField, pinpointField, docketNumberField, wlCiteField
    ).forEach { trackBorder(it) }
    run {
      val sc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(6,6,0,6) }
      form.add(JSeparator(), sc); row++
      val lcFmt = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(JLabel("Format"), lcFmt)
      val fcFmt = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      form.add(formatCombo, fcFmt)
      val bc = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.EAST }
      form.add(copyCitationBtn, bc)
      row++
      val fcPrev = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,6,6,6) }
      form.add(JScrollPane(previewArea), fcPrev)
      row++
    }
    // Wrap form to pin it to the top-left of the dialog
    val container = JPanel(java.awt.BorderLayout())
    container.add(form, java.awt.BorderLayout.NORTH)
    // Make the dialog 200px less wide by default
    run {
      val base = form.preferredSize
      val baseWidth = if (base != null && base.width > 0) base.width else 800
      val baseHeight = if (base != null && base.height > 0) base.height else 400
      val newWidth = kotlin.math.max(400, baseWidth - 200)
      container.preferredSize = java.awt.Dimension(newWidth, baseHeight)
    }
    // Now that components exist, update visibility/labels and preview
    updateContextLabels()
    updateCitationPreview()
    return container
  }

  override fun doOKAction() {
    val tSelLower = (typeField.selectedItem as? String)?.toString()?.lowercase()?.trim() ?: entry.type
    if (tSelLower == "court case") {
      val errs = mutableListOf<String>()
      val nameOk = titleField.text.trim().isNotEmpty()
      if (!nameOk) errs += "Case name (Title) is required"
      var yearTxt = yearField.text.trim()
      if (yearTxt.isEmpty() && dateField.text.trim().isNotEmpty()) {
        Regex("(19|20)\\d{2}").find(dateField.text)?.value?.let { y -> yearTxt = y; yearField.text = y }
      }
      if (!yearTxt.matches(Regex("\\d{4}"))) errs += "Year is required (4 digits)"
      val vol = reporterVolumeField.text.trim()
      val rep = reporterAbbrevField.text.trim()
      val first = firstPageField.text.trim()
      val hasReporter = vol.isNotEmpty() && rep.isNotEmpty() && first.isNotEmpty()
      val docket = docketNumberField.text.trim()
      val wl = wlCiteField.text.trim()
      val date = dateField.text.trim()
      val court = publisherField.text.trim()
      if (!hasReporter) {
        if (docket.isEmpty() || wl.isEmpty()) errs += "Provide reporter info or both Docket and WL/Lexis"
        if (date.isEmpty()) errs += "Decision Date is required for slip opinions"
        if (court.isEmpty()) errs += "Court is required for slip opinions"
      }
      if (errs.isNotEmpty()) {
        Messages.showErrorDialog(project, errs.joinToString("\n"), "Court Case Reference")
        return
      }
    } else if (tSelLower == "patent") {
      val errs = mutableListOf<String>()
      if (titleField.text.trim().isEmpty()) errs += "Title is required"
    val inventorsEmpty = authorsModel.size() == 0 && (if (corporateCheck.isSelected) authorSingleField.text.trim().isEmpty() else (authorFamilyField.text.trim().isEmpty() && authorGivenField.text.trim().isEmpty()))
      if (inventorsEmpty) errs += "At least one inventor is required"
      val issuer = publisherField.text.trim()
      if (issuer.isEmpty()) errs += "Issuing authority is required"
      val id = doiField.text.trim()
      if (id.isEmpty() || id.none { it.isDigit() }) errs += "Patent identifier must contain digits"
      // Year can be derived from Date if missing
      val y = yearField.text.trim()
      if (!y.matches(Regex("\\d{4}"))) {
        Regex("(19|20)\\d{2}").find(dateField.text)?.value?.let { yearField.text = it }
        if (!yearField.text.matches(Regex("\\d{4}"))) errs += "Year is required (4 digits)"
      }
      if (errs.isNotEmpty()) {
        Messages.showErrorDialog(project, errs.joinToString("\n"), "Patent Reference")
        return
      }
    }

    val t = (typeField.selectedItem as? String)?.toString()?.trim()?.lowercase() ?: entry.type
    val fields = mutableMapOf<String, String>()
    fields["title"] = titleField.text.trim()
    fields["year"] = yearField.text.trim()
    // Authors
    run {
      val list = collectUnifiedAuthors()
      if (list.isNotEmpty()) {
        fields["author"] = if (corporateCheck.isSelected) list.joinToString(" and ") { "{$it}" } else list.joinToString(" and ")
      }
    }
    // Court case extras
    if (t == "court case") {
      reporterVolumeField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["reporter_volume"] = it }
      reporterAbbrevField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["reporter"] = it }
      firstPageField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["first_page"] = it }
      pinpointField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["pinpoint"] = it }
      docketNumberField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["docket"] = it }
      wlCiteField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["wl"] = it }
      dateField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["date"] = it }
      publisherField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["publisher"] = it }
    }
    // Other fields
    journalField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["journal"] = it }
    volumeField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["volume"] = it }
    issueField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["number"] = it }
    pagesField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["pages"] = it }
    publisherField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["publisher"] = it }
    val id = doiField.text.trim()
    if (id.isNotEmpty()) {
      if (t == "book") fields["isbn"] = id else fields["doi"] = id
    }
    urlField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["url"] = it }
    cleanupAbstract(abstractArea.text)?.takeIf { it.isNotEmpty() }?.let { fields["abstract"] = it }
    // Keywords
    val kws = collectKeywords()
    if (kws.isNotEmpty()) fields["keywords"] = kws.joinToString(", ")
    // Meta
    val src = sourceField.text.trim(); if (src.isNotEmpty()) fields["source"] = src
    val ver = verifiedCheck.isSelected
    fields["verified"] = if (ver) "true" else "false"
    if (ver) {
      val by = verifiedByField.text.trim()
      fields["verified_by"] = if (by.isNotEmpty()) by else currentUserName()
    }
    // Created/Modified unchanged here (handled by service)

    val origType = entry.type
    val origKey = entry.key
    val newKey = keyField.text.trim().ifEmpty { entry.key }

    // Enforce unique keys across all types with merge policy
    val all = svc.readEntries()
    val conflicts = all.filter { it.key == newKey && !(it.type == origType && it.key == origKey) }
    if (conflicts.isNotEmpty()) {
      // Merge policy: prefer non-empty new values; keep existing where new is empty.
      // Special-case keywords: union lists.
      fun isBlank(v: String?): Boolean = v == null || v.trim().isEmpty()
      // Merge fields from conflicting entries into current fields
      val merged = fields.toMutableMap()
      for (c in conflicts) {
        for ((k, v) in c.fields) {
          if (k.equals("keywords", true)) {
            val cur = merged[k]?.trim().orEmpty()
            val curSet = if (cur.isEmpty()) emptySet() else cur.split(Regex("\\s*[,;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            val addSet = if (v.isEmpty()) emptySet() else v.split(Regex("\\s*[,;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val union = (curSet + addSet).toList().sorted()
            if (union.isNotEmpty()) merged[k] = union.joinToString(", ")
          } else {
            val cur = merged[k]
            if (isBlank(cur) && !isBlank(v)) merged[k] = v
          }
        }
      }
      fields.clear(); fields.putAll(merged)
    }

    val candidate = BibLibraryService.BibEntry(t, newKey, fields)
    val issues = svc.validateEntryDetailed(candidate)
    val errors = issues.filter { it.severity == BibLibraryService.Severity.ERROR }
    if (errors.isNotEmpty()) {
      val msg = errors.joinToString("\n") { "- ${it.field}: ${it.message}" }
      Messages.showErrorDialog(project, "Please fix the following before saving:\n\n$msg", "Citation Details")
      return
    }
    val saved = svc.upsertEntry(candidate)
    if (saved) {
      // Remove the original entry if type/key changed
      if (t != origType || newKey != origKey) svc.deleteEntry(origType, origKey)
      // Remove any other duplicates of the same key under different types
      for (c in conflicts) {
        if (c.type != t) svc.deleteEntry(c.type, c.key)
      }
      super.doOKAction()
      onSaved()
    } else {
      Messages.showErrorDialog(project, "Failed to save entry.", "Bibliography")
    }
  }

  private fun updateAuthorModeVisibility() {
    authorModeLayout.show(authorModeCards, if (corporateCheck.isSelected) "corp" else "person")
    authorsListLabel.text = if (corporateCheck.isSelected) "Corporate Authors" else "Authors"
    // Single unified list is always visible; only input mode changes
    authorsScrollRef.isVisible = true
  }

  private fun sanitizeSource(s: String?): String? {
    val v = (s ?: "").trim().lowercase()
    if (v.isEmpty()) return null
    val m = mapOf(
      "automated (jetbrains ai)" to "automated (JetBrains AI)",
      "manual" to "manual",
      "automated (google)" to "automated (Google)",
      "automated (crossref)" to "automated (CrossRef)",
      "automated (openlibrary)" to "automated (OpenLibrary)",
      "automated (isbndb)" to "automated (ISBNdb)"
    )
    return m[v] ?: s
  }

  private fun currentPreviewEntry(): BibLibraryService.BibEntry {
    val typeSel = (typeField.selectedItem as? String)?.toString()?.trim()?.lowercase() ?: entry.type
    val fields = mutableMapOf<String, String>()
    fields["title"] = titleField.text.trim()
    val y = yearField.text.trim(); if (y.isNotEmpty()) fields["year"] = y
    run {
      val list = collectUnifiedAuthors()
      if (list.isNotEmpty()) {
        fields["author"] = if (corporateCheck.isSelected) list.joinToString(" and ") { "{$it}" } else list.joinToString(" and ")
      }
    }
    journalField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["journal"] = it }
    volumeField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["volume"] = it }
    issueField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["number"] = it }
    pagesField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["pages"] = it }
    publisherField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["publisher"] = it }
    val id = doiField.text.trim()
    if (id.isNotEmpty()) {
      if (typeSel == "book") fields["isbn"] = id else fields["doi"] = id
    }
    urlField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["url"] = it }
    cleanupAbstract(abstractArea.text)?.takeIf { it.isNotEmpty() }?.let { fields["abstract"] = it }
    // Court case preview fields
    reporterVolumeField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["reporter_volume"] = it }
    reporterAbbrevField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["reporter"] = it }
    firstPageField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["first_page"] = it }
    pinpointField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["pinpoint"] = it }
    docketNumberField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["docket"] = it }
    wlCiteField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["wl"] = it }
    return BibLibraryService.BibEntry(typeSel, "preview-key", fields)
  }

  private fun updateCitationPreview() {
    clearPreviewHighlights()
    val e = currentPreviewEntry()
    val style = (formatCombo.selectedItem as? String) ?: "APA 7"
    val text = when (style) {
      "APA 7" -> svc.formatCitationApa(e)
      "MLA" -> svc.formatCitationMla(e)
      "Chicago" -> svc.formatCitationChicago(e)
      "IEEE" -> svc.formatCitationIeee(e)
      else -> svc.formatCitationApa(e)
    }
    previewArea.text = text ?: ""
    validateForStyleAndHighlight(style, e)
  }

  private fun copyCurrentCitation() {
    val txt = previewArea.text.trim()
    if (txt.isEmpty()) {
      Messages.showInfoMessage(project, "No citation to copy.", "Citation")
      return
    }
    try {
      java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(txt), null)
      Messages.showInfoMessage(project, "Citation copied to clipboard.", "Citation")
    } catch (_: Throwable) {
      Messages.showInfoMessage(project, txt, "Citation")
    }
  }

  private fun clearPreviewHighlights() {
    fun reset(c: JComponent) { c.border = defaultBorders[c] }
    listOf(titleField, yearField, dateField, publisherField, doiField, urlField, authorsList,
      reporterVolumeField, reporterAbbrevField, firstPageField, pinpointField, docketNumberField, wlCiteField
    ).forEach { reset(it) }
  }

  private fun validateForStyleAndHighlight(style: String, e: BibLibraryService.BibEntry) {
    val t = e.type.lowercase()
    val f = e.fields
    fun mark(comp: JComponent) { comp.border = BorderFactory.createLineBorder(Color(0xD32F2F)) }
    fun has(k: String) = f[k]?.trim().orEmpty().isNotEmpty()
    fun valOf(k: String) = f[k]?.trim().orEmpty()
    if (t == "court case") {
      if (!has("title")) mark(titleField)
      val year = valOf("year"); if (!year.matches(Regex("\\d{4}"))) mark(yearField)
      val hasReporter = has("reporter_volume") || has("reporter") || has("first_page")
      val hasSlip = has("docket") || has("wl")
      if (hasReporter || !hasSlip) {
        if (!has("reporter_volume")) mark(reporterVolumeField)
        if (!has("reporter")) mark(reporterAbbrevField)
        if (!has("first_page")) mark(firstPageField)
      } else {
        if (!has("docket")) mark(docketNumberField)
        if (!has("wl")) mark(wlCiteField)
        if (!has("date")) mark(dateField)
        if (!has("publisher")) mark(publisherField)
      }
      val u = valOf("url"); if (u.isNotEmpty()) {
        val ok = try { (u.startsWith("http://") || u.startsWith("https://")) && java.net.URI(u).isAbsolute } catch (_: Throwable) { false }
        if (!ok) mark(urlField)
      }
      return
    }
    fun requiredFor(style: String, t: String): Set<String> = when (style) {
      "APA 7" -> when (t) {
        "article" -> setOf("author", "title", "year", "journal")
        "book" -> setOf("author", "title", "year", "publisher")
        "website" -> setOf("title", "publisher", "url")
        "inproceedings", "conference paper" -> setOf("author", "title", "year")
        "patent" -> setOf("author", "title", "date", "year", "publisher", "doi")
        else -> emptySet()
      }
      "MLA" -> when (t) {
        "article" -> setOf("author", "title", "journal")
        "book" -> setOf("author", "title", "publisher")
        "website" -> setOf("title", "publisher", "url")
        "inproceedings", "conference paper" -> setOf("author", "title")
        "patent" -> setOf("author", "title", "publisher", "doi", "year")
        else -> emptySet()
      }
      "Chicago" -> when (t) {
        "article" -> setOf("author", "title", "journal", "year")
        "book" -> setOf("author", "title", "publisher", "year")
        "website" -> setOf("title", "publisher", "url")
        "inproceedings", "conference paper" -> setOf("author", "title", "year")
        "patent" -> setOf("author", "title", "date", "publisher", "doi")
        else -> emptySet()
      }
      "IEEE" -> when (t) {
        "article" -> setOf("author", "title", "journal", "year")
        "book" -> setOf("author", "title", "publisher", "year")
        "website" -> setOf("title", "publisher", "url")
        "inproceedings", "conference paper" -> setOf("author", "title", "year")
        "patent" -> setOf("author", "title", "date", "year", "doi")
        else -> emptySet()
      }
      else -> emptySet()
    }
    val req = requiredFor(style, t)
    if ("author" in req && !has("author")) { mark(authorsList) }
    if ("title" in req && !has("title")) mark(titleField)
    if ("year" in req) {
      val y = valOf("year"); if (!y.matches(Regex("\\d{4}"))) mark(yearField)
    }
    if ("date" in req && !has("date")) mark(dateField)
    if ("journal" in req && !has("journal")) mark(journalField)
    if ("publisher" in req && !has("publisher")) mark(publisherField)
    // Validate identifier (DOI/ISBN/Patent Id) whether required or provided
    run {
      val raw = valOf("doi").ifEmpty { valOf("isbn") }
      val idVal = raw.trim()
      val required = "doi" in req
      if (idVal.isEmpty()) {
        if (required) mark(doiField)
      } else {
        val ok = when (t) {
          "book" -> isValidIsbn(idVal)
          "patent" -> idVal.any { it.isDigit() }
          else -> isValidDoi(idVal)
        }
        if (!ok) mark(doiField)
      }
    }
    if ("url" in req) {
      val u = valOf("url")
      val ok = if (u.isEmpty()) false else try { (u.startsWith("http://") || u.startsWith("https://")) && java.net.URI(u).isAbsolute } catch (_: Throwable) { false }
      if (!ok) mark(urlField)
    } else {
      val u = valOf("url")
      if (u.isNotEmpty()) {
        val ok = try { (u.startsWith("http://") || u.startsWith("https://")) && java.net.URI(u).isAbsolute } catch (_: Throwable) { false }
        if (!ok) mark(urlField)
      }
    }
  }

  private fun isValidDoi(s: String): Boolean {
    val v = s.trim()
    // Basic Crossref-style DOI format validation
    return v.matches(Regex("(?i)^10\\.\\S+/.+"))
  }
  private fun isValidIsbn(raw: String): Boolean {
    val s = raw.uppercase().replace("[^0-9X]".toRegex(), "")
    return when (s.length) {
      10 -> isValidIsbn10(s)
      13 -> isValidIsbn13(s)
      else -> false
    }
  }
  private fun isValidIsbn10(s: String): Boolean {
    if (s.length != 10) return false
    var sum = 0
    for (i in 0 until 9) {
      val c = s[i]
      if (!c.isDigit()) return false
      sum += (10 - i) * (c - '0')
    }
    val last = s[9]
    sum += if (last == 'X') 10 else if (last.isDigit()) (last - '0') else return false
    return sum % 11 == 0
  }
  private fun isValidIsbn13(s: String): Boolean {
    if (s.length != 13 || s.any { !it.isDigit() }) return false
    var sum = 0
    for (i in 0 until 12) {
      val d = s[i] - '0'
      sum += if (i % 2 == 0) d else d * 3
    }
    val check = (10 - (sum % 10)) % 10
    return check == (s[12] - '0')
  }

  private fun updateContextLabels() {
    // Guard for early calls before UI components initialize
    if (!::authorModeCards.isInitialized || !::authorsScrollRef.isInitialized) return
    val t = (typeField.selectedItem as? String)?.lowercase()?.trim() ?: ""
    val (creatorSingular, _) = when (t) {
      "movie/film", "video" -> "Director" to "Directors"
      "tv/radio broadcast" -> "Director" to "Directors"
      "song" -> "Artist" to "Artists"
      "speech" -> "Speaker" to "Speakers"
      "patent" -> "Inventor" to "Inventors"
      else -> "Author" to "Authors"
    }
    authorLabel.text = creatorSingular
    authorsListLabel.text = when (t) {
      "movie/film", "video", "tv/radio broadcast" -> "Directors"
      "song" -> "Artists"
      "speech" -> "Speakers"
      "patent" -> "Inventors"
      else -> "Authors"
    }
    publisherLabel.text = when (t) {
      "movie/film", "video" -> "Studio"
      "tv/radio broadcast" -> "Network"
      "song" -> "Label"
      "patent" -> "Issuing authority"
      "court case" -> "Court"
      else -> "Publisher"
    }
    idLabel.text = when (t) {
      "patent" -> "Patent Identifier"
      else -> "DOI/ISBN"
    }
    val showId = t != "website" && t != "court case"
    idLabel.isVisible = showId
    doiField.isVisible = showId
    lookupBtn.isVisible = showId
    val showJournal = t != "website" && t != "court case"
    journalLabel.isVisible = showJournal
    journalField.isVisible = showJournal
    volumeLabel.isVisible = showJournal
    volumeField.isVisible = showJournal
    issueLabel.isVisible = showJournal
    issueField.isVisible = showJournal
    pagesLabel.isVisible = showJournal
    pagesField.isVisible = showJournal
    val showDate = t == "patent" || t == "court case"
    dateLabel.isVisible = showDate
    dateField.isVisible = showDate
    dateLabel.text = if (t == "court case") "Decision Date" else if (t == "patent") "Date" else dateLabel.text
    val isCase = t == "court case"
    authorLabel.isVisible = !isCase
    authorModeCards.isVisible = !isCase
    authorsListLabel.isVisible = !isCase
    authorsScrollRef.isVisible = !isCase
    // No separate corporate list/input; unified list always visible when not a court case
    corporateCheck.isVisible = !isCase
    reporterVolumeLabel.isVisible = isCase
    reporterVolumeField.isVisible = isCase
    reporterAbbrevLabel.isVisible = isCase
    reporterAbbrevField.isVisible = isCase
    firstPageLabel.isVisible = isCase
    firstPageField.isVisible = isCase
    pinpointLabel.isVisible = isCase
    pinpointField.isVisible = isCase
    docketNumberLabel.isVisible = isCase
    docketNumberField.isVisible = isCase
    wlCiteLabel.isVisible = isCase
    wlCiteField.isVisible = isCase
  }

  private fun addKeywordFromField() {
    val kw = keywordInputField.text.trim()
    if (kw.isEmpty()) return
    keywordsModel.addElement(kw)
    keywordInputField.text = ""
    keywordInputField.requestFocusInWindow()
    sortKeywordsModel()
  }
  private fun removeSelectedKeywords() {
    val idxs = keywordsList.selectedIndices
    if (idxs == null || idxs.isEmpty()) return
    for (i in idxs.sortedDescending()) {
      if (i >= 0 && i < keywordsModel.size()) keywordsModel.remove(i)
    }
    sortKeywordsModel()
  }
  private fun editSelectedKeyword() {
    val i = keywordsList.selectedIndex
    if (i < 0) return
    val v = keywordsModel.get(i)
    keywordsModel.remove(i)
    keywordInputField.text = v
    keywordInputField.requestFocusInWindow()
    sortKeywordsModel()
  }

  private fun collectKeywords(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until keywordsModel.size()) list += keywordsModel.elementAt(i)
    val pending = keywordInputField.text.trim()
    if (pending.isNotEmpty()) list += pending
    return list.map { it.trim() }.filter { it.isNotEmpty() }
  }

  private fun collectUnifiedAuthors(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until authorsModel.size()) list += authorsModel.elementAt(i)
    if (corporateCheck.isSelected) {
      val pending = authorSingleField.text.trim()
      if (pending.isNotEmpty()) list += pending
    } else {
      val fam = authorFamilyField.text.trim(); val giv = authorGivenField.text.trim()
      if (fam.isNotEmpty() || giv.isNotEmpty()) list += if (giv.isNotEmpty()) "$fam, $giv" else fam
    }
    return list.map { it.trim() }.filter { it.isNotEmpty() }
  }
  private fun moveAuthorsUp() { moveUp(authorsList, authorsModel) }
  private fun moveAuthorsDown() { moveDown(authorsList, authorsModel) }
  private fun <T> moveUp(list: JList<*>, model: DefaultListModel<T>) {
    val idx = list.selectedIndex
    if (idx <= 0) return
    val value = model.getElementAt(idx)
    model.remove(idx)
    model.add(idx - 1, value)
    list.selectedIndex = idx - 1
  }
  private fun <T> moveDown(list: JList<*>, model: DefaultListModel<T>) {
    val idx = list.selectedIndex
    if (idx < 0 || idx >= model.size() - 1) return
    val value = model.getElementAt(idx)
    model.remove(idx)
    model.add(idx + 1, value)
    list.selectedIndex = idx + 1
  }

  // Authors helpers
  private fun addAuthorFromInput() {
    if (corporateCheck.isSelected) {
      val v = authorSingleField.text.trim()
      if (v.isEmpty()) return
      authorsModel.addElement(v)
      authorSingleField.text = ""
      authorSingleField.requestFocusInWindow()
    } else {
      val fam = authorFamilyField.text.trim()
      val giv = authorGivenField.text.trim()
      if (fam.isEmpty() && giv.isEmpty()) return
      val s = if (giv.isNotEmpty()) "$fam, $giv" else fam
      authorsModel.addElement(s)
      authorFamilyField.text = ""
      authorGivenField.text = ""
      authorFamilyField.requestFocusInWindow()
    }
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
    val v = authorsModel.get(i)
    authorsModel.remove(i)
    if (corporateCheck.isSelected) {
      authorSingleField.text = v
      authorSingleField.requestFocusInWindow()
    } else {
      val idx = v.indexOf(',')
      if (idx > 0) {
        authorFamilyField.text = v.substring(0, idx).trim()
        authorGivenField.text = v.substring(idx + 1).trim()
      } else {
        val tokens = v.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isNotEmpty()) {
          authorFamilyField.text = tokens.last()
          authorGivenField.text = tokens.dropLast(1).joinToString(" ")
        } else {
          authorFamilyField.text = ""
          authorGivenField.text = ""
        }
      }
      authorFamilyField.requestFocusInWindow()
    }
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
  // collectAuthors removed: unified authors handled by collectUnifiedAuthors()

  // Normalize abstract whitespace for clean display and wrapping
  private fun cleanupAbstract(raw: String?): String? {
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

  private fun currentUserName(): String {
    val u = System.getProperty("user.name")?.trim().orEmpty()
    return if (u.isNotEmpty()) u else "user"
  }
}
