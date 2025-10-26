package net.samcaldwell.latex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.util.concurrent.TimeUnit

@State(name = "LatexDependencyState", storages = [Storage("latex-deps.xml")])
@Service(Service.Level.APP)
class DependencyManager : PersistentStateComponent<DependencyManager.State> {
  private val log = Logger.getInstance(DependencyManager::class.java)

  data class State(
    var consented: Boolean = false,
    var autoInstallEnabled: Boolean = true,
    var lastCheckMs: Long = 0L,
    var installLatexmk: Boolean = true,
    var installBiber: Boolean = true,
    var preferFullTexDist: Boolean = true,
    var linuxPathUpdate: Boolean = true
  )

  private var state = State()

  override fun getState(): State = state
  override fun loadState(state: State) { this.state = state }

  fun checkAndMaybeInstall(project: Project, interactive: Boolean = true) {
    val missing = missingTools()
    if (missing.isEmpty()) return

    if (!interactive) {
      notifyMissing(project, missing)
      return
    }
    if (!state.consented) {
      val answer = Messages.showYesNoDialog(
        project,
        "This plugin can install required LaTeX tools (${missing.joinToString(", ")}) on your system.\n" +
        "This may run your system package manager and download large packages.\n\n" +
        "Proceed with installation?",
        "Install LaTeX Dependencies",
        "Install",
        "Cancel",
        null
      )
      if (answer == Messages.YES) {
        state.consented = true
      } else {
        notifyMissing(project, missing)
        return
      }
    }

    object : Task.Backgroundable(project, "Installing LaTeX Dependencies", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        val ok = installMissing(project, missing)
        ApplicationManager.getApplication().invokeLater {
          val group = NotificationGroupManager.getInstance().getNotificationGroup("LaTeX")
          if (ok) group.createNotification("LaTeX Tools", "Installation finished. You may need to restart IDE/terminal.", NotificationType.INFORMATION).notify(project)
          else group.createNotification("LaTeX Tools", "Installation failed or partially completed. See log for details.", NotificationType.WARNING).notify(project)
        }
      }
    }.queue()
  }

  fun missingTools(): List<String> {
    val need = mutableListOf<String>()
    if (state.installLatexmk && !isOnPath("latexmk") && !isOnPath("pdflatex")) need.add("latexmk/pdflatex")
    if (state.installBiber && !isOnPath("biber")) need.add("biber")
    return need
  }

  private fun notifyMissing(project: Project, missing: List<String>) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("LaTeX")
      .createNotification(
        "Missing LaTeX Dependencies",
        "Missing: ${missing.joinToString(", ")}. Use Tools → Install LaTeX Dependencies to set up.",
        NotificationType.WARNING
      ).notify(project)
  }

  private fun installMissing(project: Project, missing: List<String>): Boolean {
    val os = System.getProperty("os.name").lowercase()
    return when {
      os.contains("mac") -> installMac(missing)
      os.contains("win") -> installWindows(missing)
      else -> installLinuxLike(missing)
    }
  }

  private fun installMac(missing: List<String>): Boolean {
    // Prefer Homebrew; try minimal set if full MacTeX is not desired
    val brew = if (isOnPath("brew")) "brew" else return false
    var ok = true
    if (state.preferFullTexDist) {
      ok = ok && runCmd(GeneralCommandLine(brew, "install", "--cask", "mactex-no-gui"), 3600)
    } else {
      // BasicTeX is smaller; add tools as needed
      ok = ok && runCmd(GeneralCommandLine(brew, "install", "--cask", "basictex"), 3600)
    }
    if (state.installLatexmk && !isOnPath("latexmk")) ok = ok && runCmd(GeneralCommandLine(brew, "install", "latexmk"))
    if (state.installBiber && !isOnPath("biber")) ok = ok && runCmd(GeneralCommandLine(brew, "install", "biber"))
    return ok
  }

  private fun installWindows(missing: List<String>): Boolean {
    // Try Chocolatey then Scoop
    if (isOnPath("choco")) {
      var ok = true
      ok = ok && runCmd(GeneralCommandLine("choco", "install", "miktex", "-y"), 3600)
      // miktex typically provides latexmk and biber on-demand; ensure biber
      if (!isOnPath("biber")) ok = ok && runCmd(GeneralCommandLine("choco", "install", "biber", "-y"))
      return ok
    }
    if (isOnPath("scoop")) {
      var ok = true
      ok = ok && runCmd(GeneralCommandLine("scoop", "install", "miktex"), 3600)
      return ok
    }
    return false
  }

  private fun installLinuxLike(missing: List<String>): Boolean {
    // Provide best-effort for major distros when running without root prompt
    // If we don't have sudo/apt/etc. available, return false and let user run manually
    val cmds = mutableListOf<List<String>>()
    when {
      isOnPath("apt-get") -> {
        val base = if (state.preferFullTexDist) listOf("texlive-full") else listOf("texlive-latex-recommended", "texlive-latex-extra")
        val extras = mutableListOf<String>()
        if (state.installBiber) extras += "biber"
        if (state.installLatexmk) extras += "latexmk"
        cmds += listOf(listOf("sudo", "apt-get", "update"), listOf("sudo", "apt-get", "install", "-y") + base + extras)
      }
      isOnPath("dnf") -> {
        val base = if (state.preferFullTexDist) listOf("texlive") else listOf("texlive-latex", "texlive-collection-latexrecommended")
        val extras = mutableListOf<String>()
        if (state.installBiber) extras += "biber"
        if (state.installLatexmk) extras += "latexmk"
        cmds += listOf(listOf("sudo", "dnf", "install", "-y") + base + extras)
      }
      isOnPath("pacman") -> {
        val base = if (state.preferFullTexDist) listOf("texlive-most") else listOf("texlive-basic", "texlive-latexextra")
        val extras = mutableListOf<String>()
        if (state.installBiber) extras += "biber"
        if (state.installLatexmk) extras += "latexmk"
        cmds += listOf(listOf("sudo", "pacman", "-Sy") + base + extras)
      }
    }
    var ok = true
    for (c in cmds) ok = ok && runCmd(GeneralCommandLine(c))
    if (ok && state.linuxPathUpdate) maybeUpdateLinuxPath()
    return ok
  }

  private fun maybeUpdateLinuxPath() {
    if (isOnPath("latexmk") || isOnPath("pdflatex")) return
    val candidates = mutableListOf<String>()
    val tlDirs = listOf("/usr/local/texlive", "/opt/texlive")
    tlDirs.forEach { base ->
      val f = java.io.File(base)
      if (f.isDirectory) {
        f.listFiles()?.sortedByDescending { it.name }?.forEach { yearDir ->
          val bin = java.io.File(yearDir, "bin")
          bin.listFiles()?.forEach { arch ->
            val path = java.io.File(arch, "").absolutePath
            if (java.io.File(arch, "latexmk").exists() || java.io.File(arch, "pdflatex").exists()) {
              candidates += path
            }
          }
        }
      }
    }
    if (candidates.isEmpty()) return
    val target = candidates.first()
    val answer = Messages.showYesNoDialog(
      null,
      "TeX binaries may not be on your PATH.\nAdd to ~/.bashrc and ~/.zshrc?\n\nexport PATH=\"$target:\$PATH\"",
      "Update Shell PATH",
      "Add",
      "Skip",
      null
    )
    if (answer != Messages.YES) return
    appendLineToProfile(java.io.File(System.getProperty("user.home"), ".bashrc"), target)
    appendLineToProfile(java.io.File(System.getProperty("user.home"), ".zshrc"), target)
  }

  private fun appendLineToProfile(file: java.io.File, dir: String) {
    try {
      val line = "\n# Added by LaTeX Tools plugin\nexport PATH=\"$dir:\$PATH\"\n"
      file.appendText(line)
    } catch (_: Throwable) {}
  }

  private fun isOnPath(name: String): Boolean {
    val path = System.getenv("PATH") ?: return false
    val sep = if (System.getProperty("os.name").lowercase().contains("win")) ";" else ":"
    return path.split(sep).any { dir ->
      val file = java.nio.file.Paths.get(dir).resolve(if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name)
      java.nio.file.Files.isExecutable(file)
    }
  }

  private fun runCmd(cmd: GeneralCommandLine, timeoutSec: Long = 900): Boolean {
    return try {
      val proc = CapturingProcessHandler(cmd).runProcess(TimeUnit.SECONDS.toMillis(timeoutSec).toInt())
      if (proc.exitCode != 0) log.warn("Command failed: ${cmd.commandLineString}\n${proc.stdout}\n${proc.stderr}")
      proc.exitCode == 0
    } catch (t: Throwable) {
      log.warn("Command failed: ${cmd.commandLineString}", t)
      false
    }
  }
}
