package com.samcaldwell.latex

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class LatexSecretsService {
  private fun attributes(name: String) = CredentialAttributes("com.samcaldwell.latex/$name")

  fun setSecret(name: String, secret: String?) {
    val attrs = attributes(name)
    if (secret.isNullOrEmpty()) {
      PasswordSafe.instance.set(attrs, null)
    } else {
      PasswordSafe.instance.set(attrs, Credentials(null, secret))
    }
  }

  fun getSecret(name: String): String? {
    val attrs = attributes(name)
    val creds = PasswordSafe.instance.get(attrs)
    return creds?.getPasswordAsString()
  }

  fun clearSecret(name: String) {
    PasswordSafe.instance.set(attributes(name), null)
  }

  // Resolve a bearer token for a given URL/host/service aliases using common key patterns.
  // Lookup order (first non-empty wins):
  //  - token.<host> (lowercased)
  //  - token.<alias> for each provided alias (lowercased)
  //  - legacy keys for compatibility (e.g., doi.authToken)
  fun getTokenForUrl(urlOrHost: String, vararg aliases: String): String? {
    val host = try {
      val u = if (urlOrHost.contains("://")) java.net.URL(urlOrHost) else null
      (u?.host ?: urlOrHost).lowercase()
    } catch (_: Throwable) { urlOrHost.lowercase() }

    val candidates = mutableListOf<String>()
    candidates += "token.$host"
    aliases.forEach { a -> candidates += "token.${a.lowercase()}" }
    // legacy
    if (host.contains("doi.org") || aliases.any { it.equals("doi", true) }) candidates += "${com.samcaldwell.latex.LatexSecretsConfigurable.DOI_TOKEN_KEY}"

    for (key in candidates) {
      val v = getSecret(key)
      if (!v.isNullOrBlank()) return v
    }
    return null
  }
}
