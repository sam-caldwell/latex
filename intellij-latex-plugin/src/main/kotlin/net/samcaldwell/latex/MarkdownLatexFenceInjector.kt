package net.samcaldwell.latex

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost

class MarkdownLatexFenceInjector : MultiHostInjector {
  override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
    if (context !is PsiLanguageInjectionHost || !context.isValidHost) return
    val vFile = context.containingFile?.virtualFile ?: return
    val name = vFile.name.lowercase()
    if (!name.endsWith(".md") && !name.endsWith(".markdown")) return

    val parentText = context.parent?.text ?: return
    val firstLineEnd = parentText.indexOf('\n')
    if (firstLineEnd <= 0) return
    val header = parentText.substring(0, firstLineEnd).trim()
    if (!header.startsWith("```") && !header.startsWith("~~~")) return
    val info = header.removePrefix("```").removePrefix("~~~").trim().lowercase()
    if (info != "latex" && info != "tex") return

    registrar
      .startInjecting(TexLanguage)
      .addPlace(null, null, context, TextRange(0, context.textLength))
      .doneInjecting()
  }

  override fun elementsToInjectIn(): List<Class<out PsiElement>> =
    listOf(PsiLanguageInjectionHost::class.java)
}
