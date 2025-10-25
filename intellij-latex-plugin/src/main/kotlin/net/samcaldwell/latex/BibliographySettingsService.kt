package net.samcaldwell.latex

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(name = "BibliographySettings", storages = [Storage("bibliography.xml")])
class BibliographySettingsService : PersistentStateComponent<BibliographySettingsService.State> {
  data class State(
    var libraryPath: String? = null
  )

  private var state: State = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
  }

  fun getLibraryPath(): String? = state.libraryPath

  fun setLibraryPath(path: String?) {
    state.libraryPath = path
  }
}

