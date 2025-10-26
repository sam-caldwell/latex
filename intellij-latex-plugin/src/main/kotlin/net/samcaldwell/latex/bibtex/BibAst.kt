package net.samcaldwell.latex.bibtex

data class BibFile(val nodes: List<BibNode>)

sealed class BibNode {
  data class Entry(
    val type: String,
    val key: String,
    val fields: List<Field>,
    val headerOffset: Int
  ) : BibNode()

  data class StringDirective(val name: String, val value: ValueExpr, val offset: Int) : BibNode()
  data class PreambleDirective(val value: ValueExpr, val offset: Int) : BibNode()
  data class CommentDirective(val text: String, val offset: Int) : BibNode()
}

data class Field(
  val name: String,
  val value: ValueExpr,
  val nameOffset: Int,
  val valueOffset: Int
)

data class ValueExpr(val parts: List<ValuePart>)

sealed class ValuePart {
  data class BracedText(val text: String) : ValuePart()
  data class QuotedText(val text: String) : ValuePart()
  data class Identifier(val name: String) : ValuePart()
  data class NumberLiteral(val text: String) : ValuePart()
}

data class ParserError(val message: String, val offset: Int)
