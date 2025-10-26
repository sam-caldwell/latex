# BibTeX/BibLaTeX Parser (Verify/Reformat)

This document describes the recursive‑descent parser and lexer that power the Verify and Reformat features in the IntelliJ LaTeX/Bibliography plugin. It focuses on the concrete grammar, tokenization, error recovery, and the semantic rules applied downstream (canonicalization, validation, and normalization).

The implementation lives under:
- `intellij-latex-plugin/src/main/kotlin/net/samcaldwell/latex/bibtex/`
  - `BibLexer.kt` – tokenization
  - `BibParser.kt` – recursive‑descent parser → AST
  - `BibAst.kt` – AST node/part/value types

## Design Goals

- Treat braced values as opaque, contiguous blocks at the top level (e.g., `abstract = {…}` and `title = {…}` are a single unit).
- Accept both canonical and common BibTeX/BibLaTeX idioms (directives, entries, field values, macro concatenations via `#`).
- Be resilient to minor formatting issues and recover from local errors without cascading failures.
- Keep offsets for precise diagnostics and navigation.

## Lexical Grammar (Tokens)

Input is read as a stream of tokens. Whitespace and comments are skipped between tokens.

- `AT` → `@`
- `IDENT` → `[A-Za-z_][A-Za-z0-9_-]*`
- `NUMBER` → `\d+`
- `LBRACE`/`RBRACE` → `{` / `}`
- `LPAREN`/`RPAREN` → `(` / `)`
- `EQUALS` → `=`
- `COMMA` → `,`
- `HASH` → `#`
- `QUOTED_STRING` → `"…"` with `\` escapes
- `EOF` → end of input
- Comments: line comments begin with `%` and go to end‑of‑line.

Special lexer helper:
- `readBracedString()` reads a full braced block starting at `{`, returning the content without the outer braces, tracking nested braces. This is used to produce a single contiguous value part for `{…}`.

## AST Value Model

Value expressions are concatenations of one or more value parts, optionally joined with `#`.

```
ValueExpr := Term { '#' Term }
Term      := BracedText | QuotedText | Identifier | Number
```

The AST value parts are:
- `BracedText(text: String)` – content of a full `{…}` block (including nested braces) captured as a single part.
- `QuotedText(text: String)` – content of a `"…"` literal.
- `Identifier(name: String)` – macro/ident part.
- `NumberLiteral(text: String)` – numeric part.

Flattening helpers:
- `flattenValue(ValueExpr)` – concatenates parts (identifiers are left as names).
- `flattenValueWith(ValueExpr, stringMap)` – concatenates parts, resolving identifiers from the current `@string` map.
- Both collapse newline/indentation inside values to single spaces (verification normalization rule).

## File Grammar (EBNF)

Top level:

```
File   := { Node }
Node   := Entry | StringDirective | PreambleDirective | CommentDirective

StringDirective  := '@' 'string' DelimOpen Ident '=' ValueExpr [ { ',' } ] DelimClose
PreambleDirective:= '@' 'preamble' DelimOpen ValueExpr [ { ',' } ] DelimClose
CommentDirective := '@' 'comment' DelimOpen [ BracedText ] DelimClose

DelimOpen  := '{' | '('
DelimClose := '}' | ')'
```

Entries:

```
Entry := '@' EntryType DelimOpen Key ',' FieldList DelimClose

EntryType := IDENT (case‑insensitive)
Key       := IDENT | NUMBER | QUOTED_STRING | BracedText (trimmed)

FieldList := { [ Field | ',' ] }
Field     := FieldName FieldEquals ValueExpr [ ',' ]

FieldName  := IDENT
FieldEquals:= '=' (may be implicitly accepted if a Term token follows; see recovery)
```

Notes:
- We accept both `@type{…}` and `@type(…)` delimiters.
- The entry key after `@type{` … `,` is unbraced by definition and is not parsed as a field value.
- We accept trailing commas inside directive and entry field lists.

## Error Recovery and Robustness

The parser is designed to avoid spurious diagnostics in real‑world .bib files:

- Missing `=` resiliency: If a field name is followed by a token that clearly begins a value term (`{`, `"`, number, identifier), we proceed as if `=` were present and do not error.
- Field name recovery: If a token other than `IDENT` appears where a field name is expected, we:
  - Exit the field list if we see a closing delimiter or `EOF`.
  - Consume stray commas and continue.
  - Otherwise, advance tokens until we hit `IDENT`/`COMMA`/closer/`EOF`. If a comma is found, we consume and continue; if `IDENT`, we resume with a field; else we exit.
- Braced value capture: `readBracedString()` treats a braced block (with nested braces) as a single contiguous block so values like `abstract = {…}` or `title = {…}` are parsed as a single unit. The parser does not re‑tokenize inside the outermost braces for the purposes of field parsing.

These recovery paths prevent false positives like “Expected field name” or “Missing '=' after field name” when values are properly braced but interspersed with unexpected whitespace or comments.

## Semantics Used by Verify / Reformat

After parsing to AST:

1) `@string` expansion
   - Build a map of string macros in first‑seen order.
   - When flattening values for validation/serialization, identifiers are replaced from this map, respecting concatenation with `#`.

2) Canonicalization (field aliases)
   - Canonical field names:
     - `journal` → `journaltitle`
     - `address` → `location`
     - `school` → `institution`
   - Canonicalize maps before validation and before writing. UI may still display familiar aliases (“Journal”), but serialization uses canonicals.

3) Entry type normalization
   - Normalize common synonyms (e.g., `conference` → `inproceedings`, `website` → `online`).
   - If the header type is not in the strict BibLaTeX set, serialize as `@misc` and preserve UI type in `entrysubtype`.
   - Effective validation type is header type unless it is `misc` with a non‑blank `entrysubtype`.

4) Crossref/xdata resolution
   - For each entry, resolve `crossref` and `xdata` chains (with cycle/depth guards) and fill missing fields from parents.

5) Validation rules (selected highlights)
   - Brace wrapping: Every field value must be a single top‑level `{…}` block (ERROR) except for the entry key after `@type{key,`.
   - Dates (BibLaTeX): `date`, `eventdate`, `origdate`, `urldate` accept `YYYY`, `YYYY-MM`, `YYYY-MM-DD`, ranges `start/end` (open ends allowed), or `n.d.` (case‑insensitive).
   - Year: `YYYY` or `n.d.`.
   - Timestamps (editor metadata): `created`, `modified`, `timestamp` require ISO8601 `YYYY-MM-DDThh:mm[:ss][Z|±hh:mm]`.
   - URL: absolute `http`, `https`, `ftp` only; no spaces/control chars; percent encodings must be `%HH`.
   - Author: BibLaTeX personal/corporate name semantics are validated (family/given, `and` separation, corporate in braces).

6) Normalization for Reformat
   - Values are serialized as `name = {value}` (single brace‑wrapped block). Multi‑line indentation and newlines are collapsed to single spaces.
   - Dates normalized to canonical (zero‑padded). Timestamps normalized to padded ISO 8601 with zone.
   - URLs normalized to RFC 3986 ASCII form where possible.
   - Directives (`@string`, `@preamble`, `@comment`) are written first in original order; entries follow sorted by `(type, author, title, year)`.
   - Fields are written in simple alphanumeric order, with a small priority group at the top.

## Formal Grammar (Summary)

```
File      := { Node }
Node      := Entry | StringDirective | PreambleDirective | CommentDirective

StringDirective   := '@' 'string' DelimOpen Ident '=' ValueExpr [ { ',' } ] DelimClose
PreambleDirective := '@' 'preamble' DelimOpen ValueExpr [ { ',' } ] DelimClose
CommentDirective  := '@' 'comment'  DelimOpen [ BracedText ] DelimClose

Entry     := '@' EntryType DelimOpen Key ',' FieldList DelimClose
EntryType := IDENT
Key       := IDENT | NUMBER | QUOTED_STRING | BracedText

FieldList := { [ Field | ',' ] }
Field     := FieldName FieldEquals ValueExpr [ ',' ]
FieldName := IDENT
FieldEquals := '=' | (implicit if ValueExpr follows)

ValueExpr := Term { '#' Term }
Term      := BracedText | QuotedText | Identifier | Number

DelimOpen  := '{' | '('
DelimClose := '}' | ')'

Identifier := IDENT
Number     := NUMBER
BracedText := '{' … '}'    (balanced; captured as a single unit)
QuotedText := '"' … '"'   (with escapes)
```

## Notes on Implementation Details

- Offsets/line:column mapping: The parser keeps offsets; `toLineCol(offset)` recomputes line/column for diagnostics and navigation.
- Error objects: `ParserError(message, offset)` are produced for structural issues the parser cannot recover from; Verify renders them with `line:col`.
- Recovery limits: The recovery loop while searching for the next field has a defensive step limit to avoid infinite scans on malformed input.

## Known Edge Cases (Handled)

- Trailing commas and stray commas between fields are tolerated.
- Implicit `=` when value clearly begins (avoids false “Missing '='”).
- Braced values with nested braces are treated as one unit (no splitting inside).
- Early closers (`}` or `)`) gracefully end the field list without spurious errors.

## Out of Scope for the Parser (Handled Semantically)

- Type aliasing and field canonicalization.
- Crossref/xdata merging.
- Date/timestamp/URL normalization and validation.
- Author name parsing/validation (performed in validation layer).

---

If you encounter a concrete input that still surfaces a false parser error, please capture the exact line(s) and we’ll add a targeted test and, if needed, adjust recovery to treat it as valid. The intent is that valid patterns like `abstract = {…},`/`title = {…},`/`journal = {…},` never produce parser diagnostics.
