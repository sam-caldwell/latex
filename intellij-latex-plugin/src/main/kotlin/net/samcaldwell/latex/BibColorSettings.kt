package net.samcaldwell.latex

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class BibColorSettings : ColorSettingsPage {
  private val descriptors = arrayOf(
    AttributesDescriptor("@ marker", BibSyntaxHighlighter.BIB_AT),
    AttributesDescriptor("Entry type", BibSyntaxHighlighter.BIB_ENTRY_TYPE),
    AttributesDescriptor("Field name", BibSyntaxHighlighter.BIB_FIELD_NAME),
    AttributesDescriptor("Equals", BibSyntaxHighlighter.BIB_EQUALS),
    AttributesDescriptor("Comma", BibSyntaxHighlighter.BIB_COMMA),
    AttributesDescriptor("Hash (concat)", BibSyntaxHighlighter.BIB_HASH),
    AttributesDescriptor("Braces/Parens", BibSyntaxHighlighter.BIB_BRACES),
    AttributesDescriptor("Number", BibSyntaxHighlighter.BIB_NUMBER),
    AttributesDescriptor("String", BibSyntaxHighlighter.BIB_STRING),
    AttributesDescriptor("Comment", BibSyntaxHighlighter.BIB_COMMENT),
    AttributesDescriptor("Abstract value", BibSyntaxHighlighter.BIB_ABSTRACT_VALUE),
    AttributesDescriptor("Title value", BibSyntaxHighlighter.BIB_TITLE_VALUE),
    AttributesDescriptor("URL value", BibSyntaxHighlighter.BIB_URL_VALUE),
  )

  override fun getIcon(): Icon? = BibFileType.icon
  override fun getHighlighter(): SyntaxHighlighter = BibSyntaxHighlighter()
  override fun getDemoText(): String = """
    % Macros
    @string{ jx = "Journal of Examples" }

    @article{sample2021,
      author = {Doe, John and Roe, Jane},
      title = {A Sample Article},
      journaltitle = {jx},
      year = {2021},
      doi = {10.1000/xyz123}
    }
  """.trimIndent()
  override fun getAdditionalHighlightingTagToDescriptorMap(): MutableMap<String, TextAttributesKey>? = null
  override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
  override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
  override fun getDisplayName(): String = "BibTeX"
}
