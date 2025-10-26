package net.samcaldwell.latex

import com.intellij.openapi.project.Project

object SpellcheckUtil {
  fun misspelledWords(project: Project?, text: String): List<String> {
    val words = Regex("[A-Za-z][A-Za-z-']{2,}").findAll(text).map { it.value }.toSet()
    if (words.isEmpty()) return emptyList()
    // Try IntelliJ SpellChecker if available
    try {
      val cls = Class.forName("com.intellij.spellchecker.SpellCheckerManager")
      val getInstance = cls.getMethod("getInstance", Project::class.java)
      val mgr = getInstance.invoke(null, project)
      val hasProblem = cls.methods.firstOrNull { it.name == "hasProblem" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
      if (mgr != null && hasProblem != null) {
        val bad = mutableListOf<String>()
        for (w in words) {
          val res = hasProblem.invoke(mgr, w) as? Boolean ?: false
          if (res) bad += w
        }
        return bad
      }
    } catch (_: Throwable) {
      // Fallback heuristic (very conservative): flag words with 3+ repeated letters or very long tokens
    }
    val bad = mutableListOf<String>()
    for (w in words) {
      if (w.length > 32) bad += w
      if (Regex("([A-Za-z])\\\\1\\\\1").containsMatchIn(w)) bad += w
    }
    return bad
  }
}

