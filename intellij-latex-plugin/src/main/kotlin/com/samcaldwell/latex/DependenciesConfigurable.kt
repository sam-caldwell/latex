package com.samcaldwell.latex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

class DependenciesConfigurable : Configurable {
  private val panel = JPanel(GridBagLayout())
  private val autoInstall = JCheckBox("Enable automatic install on startup")
  private val installLatexmk = JCheckBox("Install LaTeX engine (latexmk/pdflatex)")
  private val installBiber = JCheckBox("Install biber (BibLaTeX)")
  private val preferFull = JCheckBox("Prefer full TeX distribution (texlive-full/MacTeX)")
  private val linuxPathUpdate = JCheckBox("On Linux, add TeX bin dir to ~/.bashrc and ~/.zshrc when needed")

  init {
    var row = 0
    fun addRow(comp: JComponent) {
      val c = GridBagConstraints().apply {
        gridx = 0; gridy = row; weightx = 1.0
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(4, 6, 4, 6)
      }
      panel.add(comp, c)
      row++
    }
    addRow(JLabel("LaTeX Dependencies"))
    addRow(autoInstall)
    addRow(installLatexmk)
    addRow(installBiber)
    addRow(preferFull)
    addRow(linuxPathUpdate)
  }

  override fun createComponent(): JComponent = panel

  override fun isModified(): Boolean {
    val st = state()
    return autoInstall.isSelected != st.autoInstallEnabled ||
      installLatexmk.isSelected != st.installLatexmk ||
      installBiber.isSelected != st.installBiber ||
      preferFull.isSelected != st.preferFullTexDist ||
      linuxPathUpdate.isSelected != st.linuxPathUpdate
  }

  override fun apply() {
    val mgr = manager()
    val st = mgr.state
    st.autoInstallEnabled = autoInstall.isSelected
    st.installLatexmk = installLatexmk.isSelected
    st.installBiber = installBiber.isSelected
    st.preferFullTexDist = preferFull.isSelected
    st.linuxPathUpdate = linuxPathUpdate.isSelected
  }

  override fun reset() {
    val st = state()
    autoInstall.isSelected = st.autoInstallEnabled
    installLatexmk.isSelected = st.installLatexmk
    installBiber.isSelected = st.installBiber
    preferFull.isSelected = st.preferFullTexDist
    linuxPathUpdate.isSelected = st.linuxPathUpdate
  }

  override fun getDisplayName(): String = "LaTeX Tools (Dependencies)"

  private fun manager(): DependencyManager = ApplicationManager.getApplication().getService(DependencyManager::class.java)
  private fun state(): DependencyManager.State = manager().state
}

