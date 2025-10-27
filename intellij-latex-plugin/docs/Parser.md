# BibTeX/BibLaTeX Parser (Verify/Reformat)

This document describes the recursive‑descent parser and lexer that power the Verify and Reformat features in the 
IntelliJ LaTeX/Bibliography plugin. It focuses on the concrete grammar, tokenization, error recovery, and the semantic
rules applied downstream (canonicalization, validation, and normalization).

The implementation lives under:
- `intellij-latex-plugin/src/main/kotlin/net/samcaldwell/latex/bibtex/`
  - `BibLexer.kt` – tokenization
  - `BibParser.kt` – recursive‑descent parser → AST
  - `BibAst.kt` – AST node/part/value types


source: https://mirrors.ibiblio.org/CTAN/macros/latex/contrib/biblatex/doc/biblatex.pdf

## Lexical Grammar (Tokens)

Input is read as a stream of tokens. Whitespace and comments are skipped between tokens.

### Token set

- WS → `[ \t\r\n]+`
  - Normalize newlines as \r\n | \n | \r. Outside strings, WS is insignificant except where it separates tokens.
- LINE_COMMENT → "%" not_newline* newline
  - Discards from % to end of line. (Only %… line comments; block comments are parsed as a directive, see below.)
- AT → "@"
  - Introduces directives and entries (@string, @preamble, @comment, @<entrytype>). Case-insensitive.
- LBRACE / RBRACE → "{" / "}"
  - Used both as record delimiters and braced string delimiters. Braced strings allow nesting; lexer must track brace depth.
- LPAREN / RPAREN → "(" / ")"
  - Alternate record delimiters and for @comment(...) payloads; must be matched.
- COMMA → ","
  - Field/list separator inside records.
- EQUALS → "="
  - Field/value and @string name/value separator.
- HASH → "#"
  - BibTeX concatenation operator between value parts; optional WS around #.
- QUOTE → '"'
  - Starts/ends quoted strings; quoted strings do not allow unescaped " or newlines; they may include balanced {…} groups as text units.
- IDENT → case‑insensitive identifier (entry types, field names, string macro names)
    - Pattern:
        - IDENT_START = ASCII letter or _ or any Unicode letter
        - IDENT_CONT = letters | digits | _ | - | : | . (repeat 0+)
    - Comparisons are case‑insensitive; preserve original spelling.
- KEY → citation key following an entry’s opening delimiter
  - Pattern: one or more characters except whitespace, ,, {, }, (, ), ".
  - Keep raw lexeme.
- NUMBER → [0-9]+
  - Decimal integer atom (signs belong to higher‑level date semantics, not NUMBER).
- BRACED_STRING (composite)
  - Form: { string‑chunk* } with nesting.
  - string‑chunk ∈ { TEXT, BRACED_STRING }.
  - TEXT: any char except { or } (Unicode allowed).
  - TeX escapes (e.g., \{"o}) are plain text; only braces affect structure.
- QUOTED_STRING (composite)
  - Form: " q‑chunk* ".
  - q‑chunk ∈ { QTEXT, BRACED_STRING }.
  - QTEXT: any char except " or newline.
- DIRECTIVE (recognized after @, case‑insensitive literals)
  - string → @string{ name = value } or paren form.
  - preamble → @preamble{ value } or paren form (value supports concatenation).
  - comment → @comment{ ... } or paren form; payload is opaque (no field tokenization).

- Lexing Modes (Required)
  - Default
    - Recognize @, punctuation, IDENT, NUMBER, KEY, LINE_COMMENT, WS.
  - InQuotedString
    - Collect QUOTED_STRING, allowing embedded BRACED_STRING groups; reject raw newlines and unescaped ".
  - InBracedString / InBlob
    - Maintain nesting depth for {…} (and for @comment(...), paren depth). Return when depth returns to zero.

- Operator/Keyword Sensitivity
  - Case‑insensitive: @<word> (entry types/directives), field names, string macro names, and the literal separator and used later to split name lists. Do not reserve and at lex time; splitting is a semantic step over field values.
  - Concatenation: Only # between value parts (IDENT, NUMBER, BRACED_STRING, QUOTED_STRING). Optional WS around #.

- Unicode & Escapes
  - Accept UTF‑8 across TEXT/QTEXT and identifiers. Preserve original bytes for round‑trip output.
  - Backslash sequences are not lexical escapes; treat them as ordinary text inside strings. Brace/paren matching alone governs grouping.

- Minimal Parser Precedence (Context for Syntax Phase)
  Within a value:
  ```
  VALUE := PART ( WS? '#' WS? PART )*
  PART  := IDENT | NUMBER | BRACED_STRING | QUOTED_STRING
  ```
  No other operators exist at the lexical level.

## File Grammar (EBNF)
(* ---------- File & Top-level ---------- *)
file            = { wsp | line_comment | block_comment | directive | entry } ;

directive       = preamble | stringdef | comment_directive ;

preamble        = "@" , (? case-insensitive ? "preamble") , payload ;
stringdef       = "@" , (? i ? "string")   , defpair ;
comment_directive= "@" , (? i ? "comment") , payload ;

entry           = "@" , entrytype , record ;

(* ---------- Records & payloads ---------- *)
record          = braced_record | paren_record ;
braced_record   = "{" , wsp , key , fields_opt , wsp , "}" ;
paren_record    = "(" , wsp , key , fields_opt , wsp , ")" ;

fields_opt      = [ [ key , wsp , "," , wsp ] , fieldlist , [ wsp , "," ] ] ;
fieldlist       = field , { wsp , "," , wsp , field } ;
field           = ident , wsp , "=" , wsp , value ;

defpair         = braced_defpair | paren_defpair ;
braced_defpair  = "{" , wsp , ident , wsp , "=" , wsp , value , wsp , "}" ;
paren_defpair   = "(" , wsp , ident , wsp , "=" , wsp , value , wsp , ")" ;

payload         = braced_blob | paren_blob ;
braced_blob     = "{" , { blob_item } , "}" ;
blob_item       = blob_text | braced_blob ;
paren_blob      = "(" , { paren_item } , ")" ;
paren_item      = paren_text | paren_blob ;

(* ---------- Values & atoms ---------- *)
value           = part , { wsp , "#" , wsp , part } ;          (* BibTeX ‘#’ concat *)
part            = braced_string | quoted_string | number | ident ;

braced_string   = "{" , { string_item } , "}" ;                (* nested braces ok *)
string_item     = br_text | braced_string ;

quoted_string   = '"' , { qt_text | braced_string } , '"' ;

number          = digit , { digit } ;
ident           = ident_start , { ident_cont } ;
ident_start     = letter | "_" ;
ident_cont      = letter | digit | "_" | "-" | ":" | "." ;
key             = key_char , { key_char } ;
entrytype       = ident ;                                      (* e.g., article, book, inproceedings, proceedings, xdata, set, … *)

(* ---------- Lexical trivia ---------- *)
line_comment    = "%" , { not_newline } , newline ;
wsp             = { space | tab | newline } ;
space           = " " ; tab = "\t" ;
newline         = "\r\n" | "\n" | "\r" ;
not_newline     = ? any char except newline ? ;
letter          = ? Unicode letter ? ;
digit           = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
key_char        = ? any Unicode char except whitespace, ",", "{", "}", "(", ")", '"' ? ;
br_text         = ? any char except "{" | "}" ? ;
qt_text         = ? any char except '"' | newline ? ;
blob_text       = ? any char except "{" | "}" ? ;
paren_text      = ? any char except "(" | ")" ? ;

(* ===================================================================== *)
(*                 biblatex-aware value subgrammars                       *)
(* ===================================================================== *)

(* ---------- Name lists (all name fields) ---------- *)
(* A name list is one or more names separated by the literal word 'and'. *)
name_list       = name , { wsp , (? i ? "and") , wsp , name } ;

(* A name may be:
- “simple” BibTeX form:   [von-part ] family [ "," given [ "," suffix ] ]
- or the extended key=val form (recommended by biber/biblatex). *)
  name            = extended_name | simple_name ;

(* Extended name format (may be mixed in name_list).
Keys commonly include: family, given, prefix, suffix, id, useprefix, etc.
Values are braced/quoted strings per ‘part’. *)
extended_name   = name_kv , { wsp , "," , wsp , name_kv } ;
name_kv         = name_key , wsp , "=" , wsp , name_val ;
name_key        = ident ;                       (* e.g., family|given|prefix|suffix|id … *)
name_val        = part | braced_string ;        (* usual value atoms *)

(* Simple BibTeX name syntax. Comma forms are optional; protect case with braces. *)
simple_name     = [ von_part , wsp ] , family ,
[ wsp , "," , wsp , given ,
[ wsp , "," , wsp , suffix ] ] ;

von_part        = word , { wsp , word } ;       (* tokens starting lowercase *)
family          = word , { wsp , word } ;
given           = word , { wsp , word } ;
suffix          = word , { wsp , word } ;
word            = { letter | digit | "-" | "'" | "." | "~" | " " | "{" | "}" }+ ;

(* ---------- Literal lists (xsv fields in the data model) ---------- *)
(* Fields declared with format=xsv are token lists separated by a delimiter
chosen by the style (often comma or semicolon). Parser treats them as text;
splitting is a semantic step. *)
xsv_literal     = literal , { wsp , list_sep , wsp , literal } ;
literal         = part ;
list_sep        = "," | ";" | ":" ;             (* typical separators *)

(* ---------- Date/time values (ISO 8601-2 Level 1 + biblatex extensions) ---------- *)
(* Applies to date, origdate, eventdate, urldate, etc. *)
date_value      = date_point
| date_point , "/" , date_point         (* closed range *)
| date_point , "/"                      (* open-ended range *)
| "/" , date_point ;                    (* open-start range *)

date_point      = year
| year , "-" , month
| year , "-" , month , "-" , day
| "unknown" ;                           (* unspecified cases handled semantically *)

year            = [ "+" | "-" ] , digit , digit , digit , digit ;
month           = "01" | "02" | "03" | "04" | "05" | "06"
| "07" | "08" | "09" | "10" | "11" | "12" ;
day             = "01" | "02" | "03" | "04" | "05" | "06" | "07" | "08" | "09"
| "10" | "11" | "12" | "13" | "14" | "15" | "16" | "17" | "18" | "19"
| "20" | "21" | "22" | "23" | "24" | "25" | "26" | "27" | "28" | "29" | "30" | "31" ;

(* Time-of-day may be present in some fields; parse as a suffix if needed. *)
time_suffix     = "T" , hour , ":" , minute , [ ":" , second ]
, [ ( "Z" | tz_offset ) ] ;
hour            = "00" | "01" | "02" | "03" | "04" | "05" | "06" | "07" | "08" | "09"
| "10" | "11" | "12" | "13" | "14" | "15" | "16" | "17" | "18" | "19"
| "20" | "21" | "22" | "23" ;
minute          = "00" | "01" | … | "59" ;
second          = "00" | "01" | … | "59" ;
tz_offset       = ( "+" | "-" ) , hour , ":" , minute ;

(* ===================================================================== *)
(* Notes:
• Top-level syntax supports @entry{…} and @entry(…) with matching delimiters.
• Fields accept BibTeX ‘#’ concatenation of atoms.
• Name lists use literal ‘and’; extended name format is key=value pairs (e.g.,
{family=Smith, given=John}) and can be mixed with simple names.
• Date fields follow ISO 8601-2 level-1 forms and open/closed ranges with “/”.
• Field types (name vs list vs literal) and per-entry validity are enforced by
the biblatex data model (biber --validate_datamodel), not by the lexer. *)
