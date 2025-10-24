LaTeX Tools + Preview (IntelliJ Plugin)
=======================================

## Overview
- IntelliJ Platform plugin providing LaTeX authoring with a live PDF preview and BibTeX support.
- The plugin project lives in `intellij-latex-plugin/`.

## Features
- LaTeX/BibTeX file types and icons.
- Split editor: source (left) + PDF preview (right).
- Background compilation via `latexmk` (preferred) or `pdflatex` + `bibtex`/`biber`.
- Citation completion for `\cite{...}` reading project `.bib` files.
- Bibliography sidebar: create/update `library.bib` entries via a form.
- Markdown support: inject LaTeX language into ```latex fences and render fence preview.
- File templates for LaTeX Article (generic), APA7, MLA, and Chicago (Author‑Date), plus BibTeX Database.
- One‑click "Insert Bibliography Setup" action to add biblatex and `\addbibresource{library.bib}` to your document.

## Requirements
- JDK 17.
- A LaTeX distribution on PATH (TeX Live/MacTeX/MiKTeX).
- Recommended: `latexmk`; for BibLaTeX styles (APA/MLA/Chicago): `biber`.

## Build/Run
- Open `intellij-latex-plugin/` in IntelliJ IDEA as a Gradle project (use JDK 17).
- Run Gradle task `runIde` to start a sandbox IDE with the plugin.

## Make Targets
- `make lint` — run Gradle `check` (quality/tests)
- `make test` — run tests
- `make build` — build plugin ZIP under `intellij-latex-plugin/build/distributions/`
- `make run` — launch sandbox IDE
- `make verify` — run `verifyPlugin` compatibility checks
- `make clean` — clean outputs
- `make wrapper` — generate Gradle wrapper in plugin dir (uses system Gradle)

## Repository Layout
- `intellij-latex-plugin/` — IntelliJ plugin source, Gradle build, icons, templates.
- `.gitignore` — ignores build artifacts and IDE-specific files.

## Action IDs (Keymaps/Macros)
See `docs/ActionIDs.md` for the current action IDs you can bind in Keymap or reference from macros.
