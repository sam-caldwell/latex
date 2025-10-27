package net.samcaldwell.latex.bibtex

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

object SchemaGenerator {
  fun generateMarkdown(): String {
    val sb = StringBuilder()
    sb.appendLine("# BibLaTeX Validation Schema (Generated)")
    sb.appendLine()
    sb.appendLine("Source of truth: `BiblatexModel.kt`.")
    sb.appendLine()
    sb.appendLine("This file is generated during the build. Do not edit by hand.")
    sb.appendLine()
    sb.appendLine("## Types, Required and Recommended Fields")
    sb.appendLine()
    fun reqToText(req: BiblatexModel.Requirement): String = when (req) {
      is BiblatexModel.Requirement.AllOf -> "AllOf(" + req.fields.joinToString(", ") + ")"
      is BiblatexModel.Requirement.AnyOf -> "AnyOf(" + req.fields.joinToString(", ") + ")"
    }
    val types = BiblatexModel.allowedTypes.toList().sorted()
    for (t in types) {
      val spec = BiblatexModel.getSpec(t) ?: continue
      sb.appendLine("- $t")
      if (spec.requirements.isNotEmpty()) {
        val parts = spec.requirements.map { reqToText(it) }
        sb.appendLine("  - Required: ${parts.joinToString("; ")}")
      } else {
        sb.appendLine("  - Required: (none)")
      }
      if (spec.recommended.isNotEmpty()) {
        sb.appendLine("  - Recommended: ${spec.recommended.toList().sorted().joinToString(", ")}")
      } else {
        sb.appendLine("  - Recommended: (none)")
      }
    }
    sb.appendLine()
    sb.appendLine("## Field Kinds")
    val kinds = BiblatexModel.FIELD_KINDS.entries.groupBy({ it.value }, { it.key })
    for ((k, v) in kinds) {
      sb.appendLine("- ${k.name.lowercase()}: ${v.sorted().joinToString(", ")}")
    }
    return sb.toString()
  }
}

fun main(args: Array<String>) {
  val out = SchemaGenerator.generateMarkdown()
  // Default destination ../../../../docs/Schema.md relative to this source set at runtime
  val dest = if (args.isNotEmpty()) Path.of(args[0]) else Path.of("docs/Schema.md")
  Files.createDirectories(dest.parent)
  Files.writeString(dest, out, StandardCharsets.UTF_8)
}

