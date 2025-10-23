package com.samcaldwell.latex

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class DependencyStartupActivity : StartupActivity.DumbAware {
  override fun runActivity(project: Project) {
    val mgr = com.intellij.openapi.application.ApplicationManager.getApplication().getService(DependencyManager::class.java)
    mgr.checkAndMaybeInstall(project, interactive = true)
  }
}

