package net.samcaldwell.latex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LookupSettingsServiceTest {

  @Test
  fun `default order and providers are sane`() {
    val svc = LookupSettingsService()
    val def = LookupSettingsService.defaultOrder()

    // Happy path: default order preserved
    assertEquals(def, svc.providersForQuery(isIsbn = true))

    // Sad-ish path: when querying by title, openBD should be filtered out
    val nonIsbnProviders = svc.providersForQuery(isIsbn = false)
    assertTrue(nonIsbnProviders.contains(LookupSettingsService.PROVIDER_OPENLIBRARY))
    assertTrue(!nonIsbnProviders.contains(LookupSettingsService.PROVIDER_OPENBD))
  }

  @Test
  fun `normalize filters unknown providers and restores missing ones`() {
    val svc = LookupSettingsService()
    val bogusOrder = mutableListOf("foo", LookupSettingsService.PROVIDER_GOOGLEBOOKS)
    val bogusEnabled = mutableSetOf("foo", LookupSettingsService.PROVIDER_GOOGLEBOOKS)
    svc.loadState(LookupSettingsService.State(providerOrder = bogusOrder, enabledProviders = bogusEnabled, aiFallback = true))

    // After normalization, unknowns are removed and order contains only known providers
    val order = svc.providersForQuery(isIsbn = true)
    val known = LookupSettingsService.defaultOrder()
    assertTrue(order.isNotEmpty())
    assertTrue(order.all { it in known })

    // Enabled providers should also be normalized (no unknowns, not empty)
    // When only googlebooks was enabled initially, the normalized set still must include only known providers
    val nonIsbn = svc.providersForQuery(isIsbn = false)
    assertTrue(nonIsbn.isNotEmpty())
    assertTrue(nonIsbn.all { it in known })
  }

  @Test
  fun `enabled providers filter is respected`() {
    val svc = LookupSettingsService()
    val onlyOpenLib = mutableSetOf(LookupSettingsService.PROVIDER_OPENLIBRARY)
    val state = LookupSettingsService.State(
      providerOrder = LookupSettingsService.defaultOrder().toMutableList(),
      enabledProviders = onlyOpenLib,
      aiFallback = false
    )
    svc.loadState(state)

    val byIsbn = svc.providersForQuery(isIsbn = true)
    assertEquals(listOf(LookupSettingsService.PROVIDER_OPENLIBRARY), byIsbn)

    val byTitle = svc.providersForQuery(isIsbn = false)
    assertEquals(listOf(LookupSettingsService.PROVIDER_OPENLIBRARY), byTitle)
  }

  @Test
  fun `ai fallback flag roundtrip`() {
    val svc = LookupSettingsService()
    // default is true
    assertTrue(svc.isAiFallbackEnabled())

    val st = LookupSettingsService.State(
      providerOrder = LookupSettingsService.defaultOrder().toMutableList(),
      enabledProviders = LookupSettingsService.defaultOrder().toMutableSet(),
      aiFallback = false
    )
    svc.loadState(st)
    assertTrue(!svc.isAiFallbackEnabled())
  }
}
