package net.samcaldwell.latex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.JCheckBox
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets

class LatexSecretsConfigurable : Configurable {
  private val panel = JPanel(GridBagLayout())
  private val doiToken = JPasswordField()
  private val showDoi = JCheckBox("Show")
  private val crossrefEmail = JPasswordField()
  private val showCrossrefEmail = JCheckBox("Show")
  private val crossrefToken = JPasswordField()
  private val showCrossrefToken = JCheckBox("Show")
  private val genericHost = JTextField()
  private val genericToken = JPasswordField()
  private val showGenericToken = JCheckBox("Show")

  init {
    var row = 0
    fun addRow(label: String, comp: JComponent) {
      val lc = GridBagConstraints().apply {
        gridx = 0; gridy = row; weightx = 0.0
        insets = Insets(4, 6, 4, 6)
        fill = GridBagConstraints.HORIZONTAL
      }
      val fc = GridBagConstraints().apply {
        gridx = 1; gridy = row; weightx = 1.0
        insets = Insets(4, 6, 4, 6)
        fill = GridBagConstraints.HORIZONTAL
      }
      panel.add(JLabel(label), lc)
      panel.add(comp, fc)
      row++
    }
    doiToken.columns = 30
    val tokenPanel = JPanel()
    tokenPanel.layout = java.awt.BorderLayout()
    tokenPanel.add(doiToken, java.awt.BorderLayout.CENTER)
    tokenPanel.add(showDoi, java.awt.BorderLayout.EAST)

    val defaultEcho = doiToken.echoChar
    showDoi.addActionListener {
      doiToken.echoChar = if (showDoi.isSelected) 0.toChar() else defaultEcho
    }

    addRow("DOI Authorization Token (Bearer)", tokenPanel)

    // Crossref Contact Email (masked by default with show toggle)
    crossrefEmail.columns = 30
    val crefEmailPanel = JPanel(java.awt.BorderLayout())
    crefEmailPanel.add(crossrefEmail, java.awt.BorderLayout.CENTER)
    crefEmailPanel.add(showCrossrefEmail, java.awt.BorderLayout.EAST)
    val defaultCrefEcho = crossrefEmail.echoChar
    showCrossrefEmail.addActionListener {
      crossrefEmail.echoChar = if (showCrossrefEmail.isSelected) 0.toChar() else defaultCrefEcho
    }
    addRow("Crossref Contact Email (mailto)", crefEmailPanel)

    // Crossref token (optional)
    crossrefToken.columns = 30
    val crefTokenPanel = JPanel(java.awt.BorderLayout())
    crefTokenPanel.add(crossrefToken, java.awt.BorderLayout.CENTER)
    crefTokenPanel.add(showCrossrefToken, java.awt.BorderLayout.EAST)
    val defaultCrefTokEcho = crossrefToken.echoChar
    showCrossrefToken.addActionListener {
      crossrefToken.echoChar = if (showCrossrefToken.isSelected) 0.toChar() else defaultCrefTokEcho
    }
    addRow("Crossref API Token (optional)", crefTokenPanel)

    // Generic host token
    genericHost.columns = 30
    addRow("Host for Generic Token (e.g., api.example.com)", genericHost)
    genericToken.columns = 30
    val genTokPanel = JPanel(java.awt.BorderLayout())
    genTokPanel.add(genericToken, java.awt.BorderLayout.CENTER)
    genTokPanel.add(showGenericToken, java.awt.BorderLayout.EAST)
    val defaultGenTokEcho = genericToken.echoChar
    showGenericToken.addActionListener {
      genericToken.echoChar = if (showGenericToken.isSelected) 0.toChar() else defaultGenTokEcho
    }
    addRow("Generic API Token for Host (stored as token.<host>)", genTokPanel)
  }

  override fun createComponent(): JComponent = panel
  override fun isModified(): Boolean {
    val svc = ApplicationManager.getApplication().getService(LatexSecretsService::class.java)
    val savedDoi = svc.getSecret(DOI_TOKEN_KEY) ?: ""
    val curDoi = String(doiToken.password)
    val savedCrefEmail = svc.getSecret("crossref.email") ?: ""
    val curCrefEmail = String(crossrefEmail.password)
    val savedCrefTok = svc.getSecret("token.crossref") ?: ""
    val curCrefTok = String(crossrefToken.password)
    val curHost = genericHost.text.trim()
    val curGenTok = String(genericToken.password)
    return (curDoi != savedDoi) || (curCrefEmail != savedCrefEmail) || (curCrefTok != savedCrefTok) ||
      (curHost.isNotEmpty() && curGenTok.isNotEmpty())
  }
  override fun apply() {
    val svc = ApplicationManager.getApplication().getService(LatexSecretsService::class.java)
    val doi = String(doiToken.password).trim().ifEmpty { null }
    svc.setSecret(DOI_TOKEN_KEY, doi)
    val crefEmail = String(crossrefEmail.password).trim().ifEmpty { null }
    svc.setSecret("crossref.email", crefEmail)
    val crefTok = String(crossrefToken.password).trim().ifEmpty { null }
    svc.setSecret("token.crossref", crefTok)
    val host = genericHost.text.trim().lowercase()
    val genTok = String(genericToken.password).trim()
    if (host.isNotEmpty()) {
      svc.setSecret("token.$host", genTok.ifEmpty { null })
    }
  }
  override fun reset() {
    val svc = ApplicationManager.getApplication().getService(LatexSecretsService::class.java)
    doiToken.text = svc.getSecret(DOI_TOKEN_KEY) ?: ""
    crossrefEmail.text = svc.getSecret("crossref.email") ?: ""
    crossrefToken.text = svc.getSecret("token.crossref") ?: ""
    genericHost.text = ""
    genericToken.text = ""
  }
  override fun getDisplayName(): String = "LaTeX Tools (Secrets)"

  companion object {
    const val DOI_TOKEN_KEY = "doi.authToken"
  }
}
