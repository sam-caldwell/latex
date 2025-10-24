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
      conn.setRequestProperty("User-Agent", chromeLikeUserAgent(email))
      if (!email.isNullOrBlank()) {
        // Secondary contact channel for services that honor it
        conn.setRequestProperty("From", email)
      }

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

  fun getBytes(url: String, accept: String? = null, vararg aliases: String, timeoutMs: Int = 20000): ByteArray? {
    return try {
      val u = URL(url)
      val conn = u.openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = true
      if (accept != null) conn.setRequestProperty("Accept", accept)
      val secrets = ApplicationManager.getApplication().getService(LatexSecretsService::class.java)
      val email = secrets.getSecret("crossref.email")
      conn.setRequestProperty("User-Agent", chromeLikeUserAgent(email))
      if (!email.isNullOrBlank()) conn.setRequestProperty("From", email)
      try {
        val token = secrets.getTokenForUrl(url, *aliases)
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
      } catch (_: Throwable) {}
      conn.connectTimeout = timeoutMs
      conn.readTimeout = timeoutMs
      conn.inputStream.use { ins -> ins.readBytes() }
    } catch (_: Throwable) { null }
  }

  private fun chromeLikeUserAgent(email: String?): String {
    // Choose a common Chrome UA per OS; append plugin tag and optional contact email
    val os = System.getProperty("os.name").lowercase()
    val platform = when {
      os.contains("win") -> "Windows NT 10.0; Win64; x64"
      os.contains("mac") -> "Macintosh; Intel Mac OS X 13_7"
      else -> "X11; Linux x86_64"
    }
    val chromeVersion = "124.0.0.0"
    val base = "Mozilla/5.0 ($platform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    val tag = "LaTeXTools/0.1.0"
    return if (!email.isNullOrBlank()) "$base $tag (mailto:$email)" else "$base $tag"
  }
}
