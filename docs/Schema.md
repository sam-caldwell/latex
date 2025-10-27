# BibLaTeX Validation Schema (Code-Based)

This document summarizes the code-based biblatex model used by Verify/Reformat.
The canonical source lives in:

- `intellij-latex-plugin/src/main/kotlin/net/samcaldwell/latex/bibtex/BiblatexModel.kt`

It encodes, for each entry type:
- Required fields: conjunctions (AllOf) and disjunctions (AnyOf, i.e., “at least one of”).
- Recommended (non-critical) fields: emitted as warnings if missing.
- Field kinds: guidance for parsing/validation/highlighting (name-lists, key-lists, dates, URLs, etc.).

The parser accepts any `@<ident>` type; the model constrains semantics during Verify/Reformat.

## Types, Required and Recommended Fields

Notation:
- Required: `AllOf([...])` means all listed fields are required. `AnyOf([...])` means any one is required.
- Recommended: advisory fields; Verify emits WARN if missing.

- article
  - Required: AllOf(author, title, journaltitle); AnyOf(year, date)
  - Recommended: volume, number, pages, doi, url, urldate
- book
  - Required: AnyOf(author, editor); AllOf(title); AnyOf(year, date)
  - Recommended: edition, location, isbn, doi, url
- mvbook
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: edition, location, isbn, doi, url
- inbook
  - Required: AnyOf(author, editor); AllOf(title, booktitle); AnyOf(year, date)
  - Recommended: pages, publisher, doi, url
- bookinbook
  - Required: AnyOf(author, editor); AllOf(title, booktitle); AnyOf(year, date)
  - Recommended: pages, publisher, doi, url
- suppbook
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: publisher, doi, url
- booklet
  - Required: AllOf(title)
  - Recommended: author, date, url
- collection
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: editor, publisher, doi, url
- mvcollection
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: editor, publisher, doi, url
- incollection
  - Required: AnyOf(author, editor); AllOf(title, booktitle); AnyOf(year, date)
  - Recommended: pages, publisher, doi, url
- suppcollection
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: editor, publisher, doi, url
- dataset
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: version, doi, url, urldate
- manual
  - Required: AllOf(title)
  - Recommended: author, version, url
- misc
  - Required: (none)
  - Recommended: author, date, url
- online
  - Required: AllOf(title, url)
  - Recommended: urldate, author, date
- patent
  - Required: AllOf(author, title, number); AnyOf(year, date)
  - Recommended: holder, type, location, url, urldate, version
  - Notes: location is a key list per biblatex (scope codes)
- periodical
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: volume, number, doi, url
- suppperiodical
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: volume, number, doi, url
- proceedings
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: editor, publisher, series, volume, number, doi, url
- mvproceedings
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: editor, publisher, series, doi, url
- inproceedings
  - Required: AllOf(author, title, booktitle); AnyOf(year, date)
  - Recommended: pages, publisher, series, volume, number, doi, url
- reference
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: editor, publisher, doi, url
- mvreference
  - Required: AllOf(title); AnyOf(year, date)
  - Recommended: editor, publisher, doi, url
- inreference
  - Required: AllOf(author, title, booktitle); AnyOf(year, date)
  - Recommended: pages, publisher, doi, url
- report
  - Required: AllOf(author, title, institution); AnyOf(year, date)
  - Recommended: type, number, doi, url
- set
  - Required: AllOf(title)
  - Recommended: date
- software
  - Required: AllOf(title)
  - Recommended: version, url, urldate, publisher, doi
- thesis
  - Required: AllOf(author, title, institution); AnyOf(year, date)
  - Recommended: type, url, doi
- unpublished
  - Required: AllOf(author, title, note); AnyOf(year, date)
  - Recommended: url, urldate
- xdata
  - Required: (none)
  - Recommended: (none)

Synonyms and Aliases (normalized in code):
- conference → inproceedings
- electronic → online
- phd thesis/phd-thesis → phdthesis; masters thesis/master’s thesis/masters-thesis → mastersthesis
- tech report/technical report → techreport
- website/web → online; movie/film → movie; tv/radio broadcast → video

Custom and Domain Types (accepted, minimally constrained):
- customa–customf, artwork, audio, bibnote, commentary, image, jurisdiction, legislation, legal, letter, movie, music, performance, review, standard, video

## Field Kinds

Used by the highlighter/validator to apply appropriate parsing rules:
- Name lists: `author`, `editor`, `translator`, `holder`
- Key lists: `location` (e.g., patent jurisdictions)
- Dates: `date`, `urldate`, also `year` (integer)
- URLs/DOIs/Eprints: `url`, `doi`, `eprint`
- Numeric: `volume`, `number`
- Ranges: `pages`
- XSV: `keywords`
- Literals: `title`, `subtitle`, `titleaddon`, `booktitle`, `journaltitle`, `note`, `type`, `version`, `institution`, `publisher`, `number`

## Enforcement Semantics (Verify)

- Required (AllOf): Missing fields → ERROR.
- AnyOf: At least one required → ERROR if none present (e.g., year or date; author or editor on certain types).
- Recommended: Missing → WARNING.
- Contextual: If `url` present but `urldate` missing → WARNING.
- Patent: Requires author/title/number and year or date. The `location` field is a key list (not strictly required). Legacy use of DOI for patent identifiers is tolerated; a quick-fix can copy DOI → number.

## Keeping This Document in Sync

The source of truth is `BiblatexModel.kt`. Update that file when you need to change constraints, then reflect notable changes here. If you would like an auto-generated Schema.md, we can add a small utility that renders the model into Markdown during build.

