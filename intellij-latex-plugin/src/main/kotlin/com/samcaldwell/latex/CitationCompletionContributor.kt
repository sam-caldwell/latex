package com.samcaldwell.latex

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class CitationCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
      override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        val editor = parameters.editor
        val doc = editor.document
        val offset = parameters.offset
        val text = doc.charsSequence
        if (!isInCiteArgument(text, offset)) return

        val project = parameters.position.project
        val keys = collectBibKeys(project)
        val prefix = currentPrefix(text, offset)
        val withPrefix = result.withPrefixMatcher(prefix)
        keys.forEach { key -> withPrefix.addElement(LookupElementBuilder.create(key)) }
      }
    })
  }

  private fun isInCiteArgument(text: CharSequence, offset: Int): Boolean {
    // Simple backward scan: find last '{' and see if preceded by \cite-like command
    var i = offset - 1
    while (i >= 0 && text[i] != '{' && text[i] != '\n') i--
    if (i <= 0 || text[i] != '{') return false
    var j = i - 1
    while (j >= 0 && text[j].isWhitespace()) j--
    while (j >= 0 && text[j].isLetter()) j--
    val start = (j + 1).coerceAtLeast(0)
    if (start <= text.length - 1 && start - 1 >= 0 && text[start - 1] == '\\') {
      val cmd = text.subSequence(start, i).toString()
      return cmd.endsWith("cite") || cmd.endsWith("parencite") || cmd.endsWith("footcite")
    }
    return false
  }

  private fun currentPrefix(text: CharSequence, offset: Int): String {
    var i = offset - 1
    while (i >= 0 && text[i].isLetterOrDigit() || (i >= 0 && text[i] in listOf('-', '_', ':'))) i--
    return text.subSequence(i + 1, offset).toString()
  }

  private fun collectBibKeys(project: Project): Set<String> {
    val keys = mutableSetOf<String>()
    val pattern = Pattern.compile("@\\w+\\s*\\{\\s*([^,\\s]+)")
    val basePath = project.basePath ?: return emptySet()
    val base = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptySet()
    VfsUtil.iterateChildrenRecursively(base, { true }) { vf ->
      if (!vf.isDirectory && vf.extension?.lowercase() == "bib") {
        try {
          val content = String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
          val m = pattern.matcher(content)
          while (m.find()) keys.add(m.group(1))
        } catch (_: Throwable) { }
      }
      true
    }
    return keys
  }
}
