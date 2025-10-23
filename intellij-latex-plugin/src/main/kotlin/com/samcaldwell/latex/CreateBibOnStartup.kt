package com.samcaldwell.latex

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class CreateBibOnStartup : StartupActivity.DumbAware {
  override fun runActivity(project: Project) {
    project.getService(BibLibraryService::class.java).ensureLibraryExists()
  }
}

