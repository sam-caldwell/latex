package com.samcaldwell.latex

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "LatexLookupSettings", storages = [Storage("latexLookup.xml")])
class LookupSettingsService : PersistentStateComponent<LookupSettingsService.State> {
  data class State(
    var providerOrder: MutableList<String> = defaultOrder().toMutableList(),
    var enabledProviders: MutableSet<String> = defaultOrder().toMutableSet(),
    var aiFallback: Boolean = true
  )

  private var state: State = State()

  override fun getState(): State = state

  override fun loadState(state: State) {
    this.state = state
    normalize()
  }

  fun normalize() {
    val known = defaultOrder()
    // filter unknowns and ensure all known providers are present in order
    val filtered = state.providerOrder.filter { known.contains(it) }.toMutableList()
    for (p in known) if (!filtered.contains(p)) filtered.add(p)
    if (filtered.isEmpty()) filtered.addAll(known)
    state.providerOrder = filtered

    val enabled = state.enabledProviders.filter { known.contains(it) }.toMutableSet()
    if (enabled.isEmpty()) enabled.addAll(known)
    state.enabledProviders = enabled
  }

  fun providersForQuery(isIsbn: Boolean): List<String> {
    normalize()
    return state.providerOrder.filter { id ->
      state.enabledProviders.contains(id) && (isIsbn || id != PROVIDER_OPENBD)
    }
  }

  fun isAiFallbackEnabled(): Boolean = state.aiFallback

  companion object {
    const val PROVIDER_OPENLIBRARY = "openlibrary"
    const val PROVIDER_GOOGLEBOOKS = "googlebooks"
    const val PROVIDER_CROSSREF = "crossref"
    const val PROVIDER_WORLDCAT = "worldcat"
    const val PROVIDER_BNB = "bnb"
    const val PROVIDER_OPENBD = "openbd"
    const val PROVIDER_LOC = "loc"

    fun defaultOrder(): List<String> = listOf(
      PROVIDER_OPENLIBRARY,
      PROVIDER_GOOGLEBOOKS,
      PROVIDER_CROSSREF,
      PROVIDER_WORLDCAT,
      PROVIDER_BNB,
      PROVIDER_OPENBD,
      PROVIDER_LOC
    )

    fun providerDisplayName(id: String): String = when (id) {
      PROVIDER_OPENLIBRARY -> "OpenLibrary"
      PROVIDER_GOOGLEBOOKS -> "Google Books"
      PROVIDER_CROSSREF -> "Crossref REST"
      PROVIDER_WORLDCAT -> "WorldCat (Classify)"
      PROVIDER_BNB -> "BNB SPARQL"
      PROVIDER_OPENBD -> "openBD (Japan)"
      PROVIDER_LOC -> "US Library of Congress"
      else -> id
    }
  }
}

