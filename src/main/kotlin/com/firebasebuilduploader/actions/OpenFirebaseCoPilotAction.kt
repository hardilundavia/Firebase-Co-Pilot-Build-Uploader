package com.firebasebuilduploader.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Registered in plugin.xml under Build menu and MainToolBar.
 * Opens (or focuses) the FirebaseCoPilot tool window.
 */
class OpenFirebaseCoPilotAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager
            .getInstance(project)
            .getToolWindow("Firebase Co-Pilot: Build Uploader") ?: return

        if (!toolWindow.isVisible) {
            toolWindow.show()
        } else {
            toolWindow.activate(null)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
