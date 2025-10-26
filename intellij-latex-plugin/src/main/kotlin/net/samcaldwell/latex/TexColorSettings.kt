package net.samcaldwell.latex

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class TexColorSettings : ColorSettingsPage {
  private val descriptors = arrayOf(
    AttributesDescriptor("Command", TexSyntaxHighlighter.TEX_COMMAND),
    AttributesDescriptor("Comment", TexSyntaxHighlighter.TEX_COMMENT),
    AttributesDescriptor("Braces", TexSyntaxHighlighter.TEX_BRACES),
    AttributesDescriptor("Brackets", TexSyntaxHighlighter.TEX_BRACKETS),
    AttributesDescriptor("Math Delimiter", TexSyntaxHighlighter.TEX_MATH),
  )

  override fun getIcon(): Icon? = TexFileType.icon
  override fun getHighlighter(): SyntaxHighlighter = TexSyntaxHighlighter()
  override fun getDemoText(): String = """
    % Preamble
    \documentclass{article}
    \usepackage{amsmath}

    \begin{document}
    Hello, \textbf{world}!
    Inline math ${'$'}a^2 + b^2 = c^2${'$'} and display ${'$'}${'$'}E=mc^2${'$'}${'$'}.
    \end{document}
  """.trimIndent()
  override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null
  override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
  override fun getDisplayName(): String = "LaTeX"
}
