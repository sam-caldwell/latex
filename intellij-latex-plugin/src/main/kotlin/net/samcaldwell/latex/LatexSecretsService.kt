package net.samcaldwell.latex

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class LatexSecretsService {
  private val NEW_NS = "net.samcaldwell.latex"

  private fun attributes(ns: String, name: String) = CredentialAttributes("$ns/$name")

  fun setSecret(name: String, secret: String?) {
    if (secret.isNullOrEmpty()) {
      PasswordSafe.instance.set(attributes(NEW_NS, name), null)
    } else {
      PasswordSafe.instance.set(attributes(NEW_NS, name), Credentials(null, secret))
    }
  }

  fun getSecret(name: String): String? {
    val creds = PasswordSafe.instance.get(attributes(NEW_NS, name))
    return creds?.getPasswordAsString()
  }

  fun clearSecret(name: String) {
    PasswordSafe.instance.set(attributes(NEW_NS, name), null)
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
    // include DOI token key for convenience
    if (host.contains("doi.org") || aliases.any { it.equals("doi", true) }) candidates += "${net.samcaldwell.latex.LatexSecretsConfigurable.DOI_TOKEN_KEY}"

    for (key in candidates) {
      val v = getSecret(key)
      if (!v.isNullOrBlank()) return v
    }
    return null
  }
}
