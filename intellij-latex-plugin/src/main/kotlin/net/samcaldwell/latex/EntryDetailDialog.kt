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
  private val dateField = JTextField()
  private val dateLabel = JLabel("Date")
  private val keyField = JTextField().apply {
    columns = 20
    isEditable = false
    toolTipText = "Key is read-only"
  }
  private val journalField = JTextField()
  private val publisherField = JTextField()
  private val doiField = JTextField().apply { columns = 28 }
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
  private val sourceField = JTextField()
  private val verifiedCheck = JCheckBox()
  private val verifiedByField = JTextField()
  private val createdField = JTextField().apply { isEditable = false }
  private val modifiedField = JTextField().apply { isEditable = false }

  private val authorLabel = JLabel("Author")
  private val authorsListLabel = JLabel("Authors")
  private val publisherLabel = JLabel("Publisher")
  private val journalLabel = JLabel("Journal")
  private val idLabel = JLabel("DOI/ISBN")
  private val reporterVolumeLabel = JLabel("Volume")
  private val reporterAbbrevLabel = JLabel("Reporter")
  private val firstPageLabel = JLabel("First page")
  private val pinpointLabel = JLabel("Pinpoint")
  private val docketNumberLabel = JLabel("Docket No.")
  private val wlCiteLabel = JLabel("WL/Lexis")
  private val corporateCheck = JCheckBox("Corporate Author")
  private val corporateNameField = JTextField()
  private lateinit var authorModeCards: JPanel
  private lateinit var authorModeLayout: java.awt.CardLayout
  private lateinit var authorsScrollRef: JScrollPane
  private val corporateInputField = JTextField()
  private val corporateNamesModel = DefaultListModel<String>()
  private val corporateList = JList(corporateNamesModel).apply {
    visibleRowCount = 6
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
  }
  private val corporateListLabel = JLabel("Corporate Authors")
  private lateinit var corporateScrollRef: JScrollPane
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
    listOf(titleField, yearField, dateField, journalField, publisherField, doiField, urlField, keywordInputField, verifiedByField, sourceField).forEach { it.onUserChange() }
    abstractArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
      override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
      override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
      override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateCitationPreview()
    })
    (typeField as JComboBox<*>).addActionListener { updateCitationPreview() }
    corporateCheck.addActionListener { updateCitationPreview() }
    formatCombo.addActionListener { updateCitationPreview() }
    copyCitationBtn.addActionListener { copyCurrentCitation() }
    init()
  }

  private fun initFieldsFrom(e: BibLibraryService.BibEntry) {
    typeField.selectedItem = e.type
    titleField.text = e.fields["title"] ?: e.fields["booktitle"] ?: ""
    keyField.text = e.key
    // Populate authors or corporate names
    authorsModel.clear()
    val rawAuthor = (e.fields["author"] ?: "").trim()
    val parts = if (rawAuthor.isNotEmpty()) rawAuthor.split(Regex("""\s+and\s+""", RegexOption.IGNORE_CASE)).map { it.trim().trim('{','}') }.filter { it.isNotEmpty() } else emptyList()
    val isCorp = parts.isNotEmpty() && parts.all { !it.contains(',') }
    corporateCheck.isSelected = isCorp
    if (isCorp) {
      corporateNamesModel.clear()
      parts.forEach { corporateNamesModel.addElement(it) }
      corporateNameField.text = ""
    } else {
      parseAuthors(rawAuthor).forEach { authorsModel.addElement(it) }
      authorFamilyField.text = ""
      authorGivenField.text = ""
    }
    yearField.text = (e.fields["year"] ?: "").let { s -> Regex("""\b(\d{4})\b""").find(s)?.groupValues?.getOrNull(1) ?: "" }
    dateField.text = e.fields["date"] ?: ""
    journalField.text = e.fields["journal"] ?: ""
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
    // Title + Year + Key
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
    // Date row
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(dateLabel, lc)
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; gridwidth = 3 }
      panel.add(dateField, fc)
      row++
    }
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
    // Authors list + reordering
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
      panel.add(authorsListLabel, lc)
      val scroll = JScrollPane(authorsList).apply { preferredSize = java.awt.Dimension(200, 120) }
      authorsScrollRef = scroll
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(4,6,4,6); gridwidth = 3 }
      panel.add(scroll, fc)
      val buttons = JPanel(java.awt.GridLayout(2,1,4,4)).apply {
        val up = JButton(AllIcons.Actions.MoveUp); up.toolTipText = "Move up"; up.addActionListener { moveAuthorsUp() }; add(up)
        val dn = JButton(AllIcons.Actions.MoveDown); dn.toolTipText = "Move down"; dn.addActionListener { moveAuthorsDown() }; add(dn)
      }
      val bc = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
      panel.add(buttons, bc)
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
    // Corporate authors
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(corporateListLabel, lc)
      val input = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0))
      corporateInputField.columns = 24
      val addBtn = JButton("+")
      addBtn.addActionListener { addCorporateFromField() }
      input.add(corporateInputField)
      input.add(addBtn)
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6) }
      panel.add(input, fc)
      row++
      val scroll = JScrollPane(corporateList).apply { preferredSize = java.awt.Dimension(200, 100) }
      corporateScrollRef = scroll
      val fc2 = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(4,6,4,6); gridwidth = 3 }
      panel.add(scroll, fc2)
      val buttons = JPanel(java.awt.GridLayout(2,1,4,4)).apply {
        val up = JButton(AllIcons.Actions.MoveUp); up.toolTipText = "Move up"; up.addActionListener { moveCorporateUp() }; add(up)
        val dn = JButton(AllIcons.Actions.MoveDown); dn.toolTipText = "Move down"; dn.addActionListener { moveCorporateDown() }; add(dn)
      }
      val bc = GridBagConstraints().apply { gridx = 4; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.NORTHWEST }
      panel.add(buttons, bc)
      row++
      val popup = JPopupMenu().apply { val del = JMenuItem("Delete"); del.addActionListener { removeSelectedCorporate() }; add(del) }
      corporateList.addMouseListener(object : java.awt.event.MouseAdapter() {
        private fun show(e: java.awt.event.MouseEvent) {
          val idx = corporateList.locationToIndex(e.point)
          if (idx >= 0) {
            val sel = corporateList.selectedIndices.toSet(); if (idx !in sel) corporateList.selectedIndex = idx
          }
          popup.show(corporateList, e.x, e.y)
        }
        override fun mousePressed(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e)) show(e) }
        override fun mouseReleased(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) show(e) }
        override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) editSelectedCorporate() }
      })
    }
    // Court case: reporter info
    run {
      val lcRv = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(reporterVolumeLabel, lcRv)
      val fcRv = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(reporterVolumeField, fcRv)
      val lcRa = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      panel.add(reporterAbbrevLabel, lcRa)
      val fcRa = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 }
      panel.add(reporterAbbrevField, fcRa)
      row++
      val lcFp = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(firstPageLabel, lcFp)
      val fcFp = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(firstPageField, fcFp)
      val lcPin = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      panel.add(pinpointLabel, lcPin)
      val fcPin = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      panel.add(pinpointField, fcPin)
      row++
      val lcDock = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(docketNumberLabel, lcDock)
      val fcDock = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(docketNumberField, fcDock)
      val lcWl = GridBagConstraints().apply { gridx = 2; gridy = row; insets = Insets(4,12,4,6); anchor = GridBagConstraints.WEST }
      panel.add(wlCiteLabel, lcWl)
      val fcWl = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,0,4,6); anchor = GridBagConstraints.WEST }
      panel.add(wlCiteField, fcWl)
      row++
    }
    // Journal row
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
      panel.add(journalLabel, lc)
      panel.add(journalField, fc)
      row++
    }
    run {
      val lc = GridBagConstraints().apply { gridx = 0; gridy = row; weightx = 0.0; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      val fc = GridBagConstraints().apply { gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4,6,4,6); gridwidth = 3 }
      panel.add(publisherLabel, lc)
      panel.add(publisherField, fc)
      row++
    }
    // DOI/ISBN + URL row
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
    // Keyword input + list
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
      panel.add(header, lc); row++
      val sc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,6,6,6) }
      panel.add(JSeparator(), sc); row++
    }
    // Source + Verified + Verified By
    run {
      val lcSrc = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(JLabel("Source"), lcSrc)
      sourceField.columns = 20
      val fcSrc = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST; fill = GridBagConstraints.NONE; weightx = 0.0 }
      panel.add(sourceField, fcSrc)

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
    // Created + Modified
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
    // Toggle authors mode, border tracking, preview controls
    corporateCheck.addActionListener { updateAuthorModeVisibility() }
    updateAuthorModeVisibility()
    listOf(titleField, yearField, dateField, publisherField, doiField, urlField, authorsList, corporateList,
      reporterVolumeField, reporterAbbrevField, firstPageField, pinpointField, docketNumberField, wlCiteField
    ).forEach { trackBorder(it) }
    run {
      val sc = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(6,6,0,6) }
      panel.add(JSeparator(), sc); row++
      val lcFmt = GridBagConstraints().apply { gridx = 0; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(JLabel("Format"), lcFmt)
      val fcFmt = GridBagConstraints().apply { gridx = 1; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.WEST }
      panel.add(formatCombo, fcFmt)
      val bc = GridBagConstraints().apply { gridx = 3; gridy = row; insets = Insets(4,6,4,6); anchor = GridBagConstraints.EAST }
      panel.add(copyCitationBtn, bc)
      row++
      val fcPrev = GridBagConstraints().apply { gridx = 0; gridy = row; gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,6,6,6) }
      panel.add(JScrollPane(previewArea), fcPrev)
      row++
    }
    // Now that components exist, update visibility/labels and preview
    updateContextLabels()
    updateCitationPreview()
    return panel
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
      val inventorsEmpty = if (corporateCheck.isSelected) corporateNamesModel.size() == 0 else authorsModel.size() == 0
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
    if (!corporateCheck.isSelected) {
      collectAuthors().takeIf { it.isNotEmpty() }?.let { list ->
        fields["author"] = list.joinToString(" and ") { n -> if (n.given.isNotBlank()) "${n.family}, ${n.given}" else n.family }
      }
    } else {
      collectCorporate().takeIf { it.isNotEmpty() }?.let { list -> fields["author"] = list.joinToString(" and ") { "{$it}" } }
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

    val newKey = keyField.text.trim().ifEmpty { entry.key }
    val saved = svc.upsertEntry(BibLibraryService.BibEntry(t, newKey, fields))
    if (saved) {
      super.doOKAction()
      onSaved()
    } else {
      Messages.showErrorDialog(project, "Failed to save entry.", "Bibliography")
    }
  }

  private fun updateAuthorModeVisibility() {
    authorModeLayout.show(authorModeCards, if (corporateCheck.isSelected) "corp" else "person")
    authorsListLabel.text = if (corporateCheck.isSelected) "Corporate Authors" else "Authors"
    if (corporateCheck.isSelected) {
      authorsScrollRef.isVisible = false
      if (::corporateScrollRef.isInitialized) corporateScrollRef.isVisible = true
    } else {
      authorsScrollRef.isVisible = true
      if (::corporateScrollRef.isInitialized) corporateScrollRef.isVisible = false
    }
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
    if (!corporateCheck.isSelected) {
      collectAuthors().takeIf { it.isNotEmpty() }?.let { list ->
        fields["author"] = list.joinToString(" and ") { n -> if (n.given.isNotBlank()) "${n.family}, ${n.given}" else n.family }
      }
    } else {
      collectCorporate().takeIf { it.isNotEmpty() }?.let { list -> fields["author"] = list.joinToString(" and ") { "{$it}" } }
    }
    journalField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["journal"] = it }
    publisherField.text.trim().takeIf { it.isNotEmpty() }?.let { fields["publisher"] = it }
    val id = doiField.text.trim(); if (id.isNotEmpty()) fields["doi"] = id
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
    listOf(titleField, yearField, dateField, publisherField, doiField, urlField, authorsList, corporateList,
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
    if ("author" in req && !has("author")) { if (corporateCheck.isSelected) mark(corporateList) else mark(authorsList) }
    if ("title" in req && !has("title")) mark(titleField)
    if ("year" in req) {
      val y = valOf("year"); if (!y.matches(Regex("\\d{4}"))) mark(yearField)
    }
    if ("date" in req && !has("date")) mark(dateField)
    if ("journal" in req && !has("journal")) mark(journalField)
    if ("publisher" in req && !has("publisher")) mark(publisherField)
    if ("doi" in req) {
      val idVal = valOf("doi"); if (idVal.isEmpty() || (t == "patent" && idVal.none { it.isDigit() })) mark(doiField)
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
      "book" -> "ISBN"
      "article" -> "DOI"
      "patent" -> "Patent Identifier"
      else -> "DOI/ISBN"
    }
    val showId = t != "website" && t != "court case"
    idLabel.isVisible = showId
    doiField.isVisible = showId
    val showJournal = t != "website" && t != "court case"
    journalLabel.isVisible = showJournal
    journalField.isVisible = showJournal
    val showDate = t == "patent" || t == "court case"
    dateLabel.isVisible = showDate
    dateField.isVisible = showDate
    dateLabel.text = if (t == "court case") "Decision Date" else if (t == "patent") "Date" else dateLabel.text
    val isCase = t == "court case"
    authorLabel.isVisible = !isCase
    authorModeCards.isVisible = !isCase
    authorsListLabel.isVisible = !isCase
    authorsScrollRef.isVisible = !isCase
    corporateListLabel.isVisible = !isCase
    if (::corporateScrollRef.isInitialized) corporateScrollRef.isVisible = !isCase
    corporateInputField.isVisible = !isCase
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

  // Corporate authors helpers
  private fun addCorporateFromField() {
    val v = corporateInputField.text.trim()
    if (v.isEmpty()) return
    corporateNamesModel.addElement(v)
    corporateInputField.text = ""
    corporateInputField.requestFocusInWindow()
  }
  private fun removeSelectedCorporate() {
    val idxs = corporateList.selectedIndices
    if (idxs == null || idxs.isEmpty()) return
    for (i in idxs.sortedDescending()) {
      if (i >= 0 && i < corporateNamesModel.size()) corporateNamesModel.remove(i)
    }
  }
  private fun editSelectedCorporate() {
    val i = corporateList.selectedIndex
    if (i < 0) return
    val v = corporateNamesModel.get(i)
    corporateNamesModel.remove(i)
    corporateInputField.text = v
    corporateInputField.requestFocusInWindow()
  }
  private fun collectCorporate(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until corporateNamesModel.size()) list += corporateNamesModel.elementAt(i)
    val pending = corporateInputField.text.trim()
    if (pending.isNotEmpty()) list += pending
    return list.map { it.trim() }.filter { it.isNotEmpty() }
  }
  private fun moveAuthorsUp() { moveUp(authorsList, authorsModel) }
  private fun moveAuthorsDown() { moveDown(authorsList, authorsModel) }
  private fun moveCorporateUp() { moveUp(corporateList, corporateNamesModel) }
  private fun moveCorporateDown() { moveDown(corporateList, corporateNamesModel) }
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

  // Structured authors helpers
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
