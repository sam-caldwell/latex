package com.samcaldwell.latex

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class LatexCompilerService(private val project: Project) {
  private val log = Logger.getInstance(LatexCompilerService::class.java)

  fun compile(file: VirtualFile): Path? {
    val workDir = Paths.get(file.parent.path)
    val baseName = PathUtil.getFileName(file.nameWithoutExtension)
    val pdfPath = workDir.resolve("$baseName.pdf")

    if (tryLatexmk(workDir, file.name) && Files.exists(pdfPath)) return pdfPath
    if (tryPdfLatexCycle(workDir, file.name) && Files.exists(pdfPath)) return pdfPath
    return null
  }

  private fun tryLatexmk(workingDir: Path, texName: String): Boolean {
    val exe = if (isOnPath("latexmk")) "latexmk" else return false
    val cmd = GeneralCommandLine(exe, "-pdf", "-interaction=nonstopmode", "-halt-on-error", "-synctex=1", texName)
      .withWorkDirectory(workingDir.toFile())
      .withEnvironment(mapOf("TEXINPUTS" to workingDir.toString() + System.getProperty("path.separator") + ""))
    return run(cmd)
  }

  private fun tryPdfLatexCycle(workingDir: Path, texName: String): Boolean {
    val pdflatex = if (isOnPath("pdflatex")) "pdflatex" else return false
    if (!run(GeneralCommandLine(pdflatex, "-interaction=nonstopmode", "-halt-on-error", texName).withWorkDirectory(workingDir.toFile()))) return false

    val aux = workingDir.resolve(texName.replaceAfterLast('.', "aux"))
    val bbl = workingDir.resolve(texName.replaceAfterLast('.', "bbl"))
    val bcf = workingDir.resolve(texName.replaceAfterLast('.', "bcf"))

    if (Files.exists(bcf) && isOnPath("biber")) {
      if (!run(GeneralCommandLine("biber", texName.removeSuffix(".tex")).withWorkDirectory(workingDir.toFile()))) return false
    } else if (
      Files.exists(aux) && !Files.exists(bbl) && isOnPath("bibtex")
    ) {
      if (!run(GeneralCommandLine("bibtex", texName.removeSuffix(".tex")).withWorkDirectory(workingDir.toFile()))) return false
    }

    // Two more passes
    if (!run(GeneralCommandLine(pdflatex, "-interaction=nonstopmode", "-halt-on-error", texName).withWorkDirectory(workingDir.toFile()))) return false
    return run(GeneralCommandLine(pdflatex, "-interaction=nonstopmode", "-halt-on-error", texName).withWorkDirectory(workingDir.toFile()))
  }

  private fun isOnPath(name: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    val sep = if (System.getProperty("os.name").lowercase().contains("win")) ";" else ":"
    return path.split(sep).any { dir ->
      val f = Paths.get(dir).resolve(if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name)
      Files.isExecutable(f)
    }
  }

  private fun run(cmd: GeneralCommandLine, timeoutSec: Long = 120): Boolean {
    return try {
      val process = CapturingProcessHandler(cmd).let { handler ->
        handler.runProcess(TimeUnit.SECONDS.toMillis(timeoutSec).toInt())
      }
      if (process.exitCode != 0) {
        log.warn("LaTeX command failed: ${cmd.commandLineString}\n${process.stdout}\n${process.stderr}")
      }
      process.exitCode == 0
    } catch (t: Throwable) {
      log.warn("LaTeX command failed: ${cmd.commandLineString}", t)
      false
    }
  }
}
