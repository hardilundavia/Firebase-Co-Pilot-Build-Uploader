package com.firebasebuilduploader.services

import com.firebasebuilduploader.model.RecentDeployment
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level persistent settings for FirebaseCoPilot.
 * Values survive IDE restarts via IntelliJ's state persistence.
 */
@State(
    name = "FirebaseCoPilotSettings",
    storages = [Storage("FirebaseCoPilot.xml")]
)
@Service(Service.Level.APP)
class FirebaseCoPilotSettingsService : PersistentStateComponent<FirebaseCoPilotSettingsService.State> {

    data class State(
        var lastServiceAccountPath: String = "",
        var lastAppId: String = "",
        var savedReleaseNotes: MutableList<String> = mutableListOf(
            "Bug fixes and performance improvements",
            "New features added",
            "UI improvements",
            "Hotfix release",
            "Beta testing build",
            "QA build"
        ),
        var recentDeployments: MutableList<RecentDeploymentState> = mutableListOf()
    )

    data class RecentDeploymentState(
        var timestamp: Long = 0L,
        var flavor: String = "",
        var buildType: String = "",
        var releaseNotes: String = "",
        var consoleUrl: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { XmlSerializerUtil.copyBean(state, myState) }

    var lastServiceAccountPath: String
        get() = myState.lastServiceAccountPath
        set(v) { myState.lastServiceAccountPath = v }

    var lastAppId: String
        get() = myState.lastAppId
        set(v) { myState.lastAppId = v }

    val savedReleaseNotes: List<String> get() = myState.savedReleaseNotes.toList()

    fun addReleaseNote(note: String) {
        if (note.isNotBlank() && !myState.savedReleaseNotes.contains(note)) {
            myState.savedReleaseNotes.add(0, note)
            if (myState.savedReleaseNotes.size > 20) {
                myState.savedReleaseNotes.removeLast()
            }
        }
    }

    fun recordDeployment(deployment: RecentDeployment) {
        myState.recentDeployments.add(0, RecentDeploymentState(
            timestamp    = deployment.timestamp,
            flavor       = deployment.flavor ?: "",
            buildType    = deployment.buildType,
            releaseNotes = deployment.releaseNotes,
            consoleUrl   = deployment.firebaseConsoleUrl
        ))
        if (myState.recentDeployments.size > 10) {
            myState.recentDeployments.removeLast()
        }
    }
}
