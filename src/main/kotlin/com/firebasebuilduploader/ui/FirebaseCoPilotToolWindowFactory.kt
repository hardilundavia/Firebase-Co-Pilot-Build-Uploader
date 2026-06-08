package com.firebasebuilduploader.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registered in plugin.xml to create the FirebaseCoPilot Tool Window.
 * The tool window appears on the right sidebar of Android Studio.
 */
class FirebaseCoPilotToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FirebaseCoPilotPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "", false)

        // Dispose coroutines when the tool window is closed
        content.setDisposer { panel.dispose() }

        toolWindow.contentManager.addContent(content)
        toolWindow.title = "Firebase Co-Pilot: Build Uploader"
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
