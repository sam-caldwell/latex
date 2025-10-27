package net.samcaldwell.latex.bibtex

data class BibFile(val nodes: List<BibNode>)

enum class Delim { BRACES, PARENS }

sealed class BibNode {
  data class Entry(
    val type: String,
    val key: String,
    val fields: List<Field>,
    val headerOffset: Int,
    val delim: Delim
  ) : BibNode()

  data class StringDirective(
    val name: String,
    val value: Value,
    val offset: Int,
    val delim: Delim
  ) : BibNode()

  data class PreambleDirective(
    val payload: Blob,
    val offset: Int,
    val delim: Delim
  ) : BibNode()

  data class CommentDirective(
    val payload: Blob,
    val offset: Int,
    val delim: Delim
  ) : BibNode()
}

data class Field(
  val name: String,
  val value: Value,
  val nameOffset: Int,
  val valueOffset: Int
)

// Value := concatenation of Parts via '#'
data class Value(val parts: List<Part>)

sealed class Part {
  data class Str(val kind: StrKind, val chunks: List<StrChunk>) : Part()
  data class Number(val lexeme: String) : Part()
  data class IdentRef(val name: String) : Part()
}

enum class StrKind { BRACED, QUOTED }

sealed class StrChunk {
  data class Text(val raw: String) : StrChunk()
  data class Group(val chunks: List<StrChunk>) : StrChunk()
}

// Blob payload for @comment/@preamble
data class Blob(val kind: Delim, val chunks: List<BlobChunk>)

sealed class BlobChunk {
  data class BlobText(val raw: String) : BlobChunk()
  data class BlobGroup(val kind: Delim, val chunks: List<BlobChunk>) : BlobChunk()
}

data class ParserError(val message: String, val offset: Int)
