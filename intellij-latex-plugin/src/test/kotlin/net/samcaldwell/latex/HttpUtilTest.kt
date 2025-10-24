package net.samcaldwell.latex

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpUtilTest {
  @AfterEach
  fun tearDown() { unmockkAll() }

  @Test
  fun `chrome-like UA includes plugin tag and optional email`() {
    // Use reflection to call the private UA builder
    val m = HttpUtil::class.java.getDeclaredMethod("chromeLikeUserAgent", String::class.java)
    m.isAccessible = true
    val inst = HttpUtil::class.java.getField("INSTANCE").get(null)
    val withEmail = m.invoke(inst, "team@example.com") as String
    val noEmail = m.invoke(inst, null) as String

    assertTrue(withEmail.contains("Mozilla/5.0"))
    assertTrue(withEmail.contains("LaTeXTools/0.1.0"))
    assertTrue(withEmail.contains("mailto:team@example.com"))

    assertTrue(noEmail.contains("Mozilla/5.0"))
    assertTrue(noEmail.contains("LaTeXTools/0.1.0"))
  }
}
