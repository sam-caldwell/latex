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
    AttributesDescriptor("Record key", BibSyntaxHighlighter.BIB_RECORD_KEY),
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
    AttributesDescriptor("Boolean value", BibSyntaxHighlighter.BIB_BOOL_VALUE),
    AttributesDescriptor("Source value", BibSyntaxHighlighter.BIB_SOURCE_VALUE),
    AttributesDescriptor("Keywords value", BibSyntaxHighlighter.BIB_KEYWORDS_VALUE),
    AttributesDescriptor("Date number", BibSyntaxHighlighter.BIB_DATE_NUMBER),
    AttributesDescriptor("Date delimiter", BibSyntaxHighlighter.BIB_DATE_DELIM),
    AttributesDescriptor("DOI value", BibSyntaxHighlighter.BIB_DOI_VALUE),
    AttributesDescriptor("Pages value", BibSyntaxHighlighter.BIB_PAGES_VALUE),
    AttributesDescriptor("Volume value", BibSyntaxHighlighter.BIB_VOLUME_VALUE),
    AttributesDescriptor("ISBN value", BibSyntaxHighlighter.BIB_ISBN_VALUE),
    AttributesDescriptor("Author name", BibSyntaxHighlighter.BIB_AUTHOR_NAME),
    AttributesDescriptor("Author 'and'", BibSyntaxHighlighter.BIB_AUTHOR_AND),
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
