package com.samcaldwell.latex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import javax.swing.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class LookupProvidersConfigurable : Configurable {
  private val panel = JPanel(BorderLayout())
  private val model = DefaultListModel<ProviderItem>()
  private val list = JList(model)
  private val aiCheckbox = JCheckBox("Use AI fallback when all sources fail")

  data class ProviderItem(val id: String, val name: String, var enabled: Boolean)

  init {
    // List with checkbox renderer
    list.selectionMode = ListSelectionModel.SINGLE_SELECTION
    list.cellRenderer = object : ListCellRenderer<ProviderItem> {
      override fun getListCellRendererComponent(list: JList<out ProviderItem>?, value: ProviderItem?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
        val cb = JCheckBox(value?.name ?: "")
        cb.isSelected = value?.enabled == true
        cb.isOpaque = true
        cb.background = if (isSelected) list?.selectionBackground else list?.background
        cb.foreground = if (isSelected) list?.selectionForeground else list?.foreground
        return cb
      }
    }
    list.addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        val idx = list.locationToIndex(e.point)
        if (idx >= 0) {
          val item = model.get(idx)
          item.enabled = !item.enabled
          list.repaint(list.getCellBounds(idx, idx))
        }
      }
    })

    val btnUp = JButton("Up")
    val btnDown = JButton("Down")
    val btnEnableAll = JButton("Enable All")
    val btnDisableAll = JButton("Disable All")
    val btnReset = JButton("Reset Defaults")

    btnUp.addActionListener { moveSelected(-1) }
    btnDown.addActionListener { moveSelected(1) }
    btnEnableAll.addActionListener { setAllEnabled(true) }
    btnDisableAll.addActionListener { setAllEnabled(false) }
    btnReset.addActionListener { resetDefaults() }

    val buttons = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
      add(btnUp); add(btnDown); add(btnEnableAll); add(btnDisableAll); add(btnReset)
    }

    val top = JPanel(BorderLayout()).apply {
      add(JLabel("Provider order (top = highest priority)"), BorderLayout.NORTH)
      add(JScrollPane(list), BorderLayout.CENTER)
    }

    panel.add(top, BorderLayout.CENTER)
    panel.add(buttons, BorderLayout.NORTH)
    panel.add(aiCheckbox, BorderLayout.SOUTH)
  }

  private fun moveSelected(delta: Int) {
    val idx = list.selectedIndex
    if (idx < 0) return
    val newIdx = idx + delta
    if (newIdx < 0 || newIdx >= model.size()) return
    val item = model.remove(idx)
    model.add(newIdx, item)
    list.selectedIndex = newIdx
  }

  private fun setAllEnabled(enabled: Boolean) {
    for (i in 0 until model.size()) model.get(i).enabled = enabled
    list.repaint()
  }

  private fun resetDefaults() {
    val svc = ApplicationManager.getApplication().getService(LookupSettingsService::class.java)
    model.clear()
    for (id in LookupSettingsService.defaultOrder()) {
      model.addElement(ProviderItem(id, LookupSettingsService.providerDisplayName(id), true))
    }
    aiCheckbox.isSelected = true
  }

  private fun loadFromService() {
    val svc = ApplicationManager.getApplication().getService(LookupSettingsService::class.java)
    svc.normalize()
    model.clear()
    for (id in svc.state.providerOrder) {
      model.addElement(ProviderItem(id, LookupSettingsService.providerDisplayName(id), svc.state.enabledProviders.contains(id)))
    }
    aiCheckbox.isSelected = svc.state.aiFallback
  }

  override fun getDisplayName(): String = "LaTeX Tools (Lookup Providers)"

  override fun createComponent(): JComponent {
    loadFromService()
    return panel
  }

  override fun isModified(): Boolean {
    val svc = ApplicationManager.getApplication().getService(LookupSettingsService::class.java)
    if (svc.state.aiFallback != aiCheckbox.isSelected) return true
    val ids = (0 until model.size()).map { model.get(it).id }
    if (ids != svc.state.providerOrder) return true
    val enabled = (0 until model.size()).filter { model.get(it).enabled }.map { model.get(it).id }.toSet()
    return enabled != svc.state.enabledProviders
  }

  override fun apply() {
    val svc = ApplicationManager.getApplication().getService(LookupSettingsService::class.java)
    val ids = (0 until model.size()).map { model.get(it).id }.toMutableList()
    val enabled = (0 until model.size()).filter { model.get(it).enabled }.map { model.get(it).id }.toMutableSet()
    svc.state.providerOrder = ids
    svc.state.enabledProviders = enabled
    svc.state.aiFallback = aiCheckbox.isSelected
    svc.normalize()
  }

  override fun reset() { loadFromService() }
}
