package com.samcaldwell.latex

import com.intellij.openapi.application.ApplicationManager
import java.net.HttpURLConnection
import java.net.URL

object HttpUtil {
  fun get(url: String, accept: String? = null, vararg aliases: String, timeoutMs: Int = 15000): String? {
    return try {
      val u = URL(url)
      val conn = u.openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = true
      if (accept != null) conn.setRequestProperty("Accept", accept)
      val secrets = ApplicationManager.getApplication().getService(LatexSecretsService::class.java)
      val email = secrets.getSecret("crossref.email")
      val ua = if (!email.isNullOrBlank()) "LaTeX Tools Plugin (mailto:$email)" else "LaTeX Tools Plugin"
      conn.setRequestProperty("User-Agent", ua)

      // Try to inject bearer token if configured
      try {
        val token = secrets.getTokenForUrl(url, *aliases)
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
      } catch (_: Throwable) {}

      conn.connectTimeout = timeoutMs
      conn.readTimeout = timeoutMs
      conn.inputStream.use { ins ->
        ins.readBytes().toString(Charsets.UTF_8)
      }
    } catch (_: Throwable) { null }
  }
}
