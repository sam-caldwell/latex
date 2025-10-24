package net.samcaldwell.latex

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

class InstallDependenciesAction : AnAction("Install LaTeX Dependencies") {
  override fun actionPerformed(e: AnActionEvent) {
    val project: Project = e.project ?: return
    val mgr = ApplicationManager.getApplication().getService(DependencyManager::class.java)
    mgr.checkAndMaybeInstall(project, interactive = true)
  }
}
