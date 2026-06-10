package com.firebasebuilduploader.model

// ── Project Structure ──────────────────────────────────────────────────────

data class AndroidProjectConfig(
    val projectName: String,
    val hasFlavors: Boolean,
    val flavorDimensions: List<String> = emptyList(),
    val flavors: List<FlavorConfig> = emptyList(),
    val buildTypes: List<String> = listOf("debug", "release")
)

data class FlavorConfig(
    val name: String,
    val dimension: String? = null,
    val applicationId: String? = null
)

// ── Build Configuration ────────────────────────────────────────────────────

data class BuildConfiguration(
    val flavor: String?,
    val buildType: String,
    val serviceAccountJsonPath: String,
    val releaseNotes: String,
    val appId: String           // extracted from service account JSON, not typed by user
) {
    fun gradleAssembleTask(): String = buildString {
        append("assemble")
        flavor?.let { append(it.replaceFirstChar(Char::uppercase)) }
        append(buildType.replaceFirstChar(Char::uppercase))
    }

    fun firebaseUploadTaskName(): String {
        val flavor    = flavor?.replaceFirstChar { it.uppercase() } ?: ""
        val buildType = buildType.replaceFirstChar { it.uppercase() }
        return "appDistributionUpload$flavor$buildType"
    }

    fun expectedApkRelativePath(moduleName: String = "app"): String {
        val flavorPath = flavor?.lowercase() ?: ""
        return if (flavor != null)
            "$moduleName/build/outputs/apk/$flavorPath/$buildType/$moduleName-$flavorPath-$buildType.apk"
        else
            "$moduleName/build/outputs/apk/$buildType/$moduleName-$buildType.apk"
    }
}

// ── Firebase ───────────────────────────────────────────────────────────────

data class FirebaseServiceAccount(
    val type: String,
    val projectId: String,
    val privateKeyId: String,
    val privateKey: String,
    val clientEmail: String,
    val clientId: String,
    val tokenUri: String
) {
    /** Derives a human-readable label shown in the UI after JSON is loaded. */
    fun displayLabel(): String = "● $clientEmail"
}

sealed class UploadResult {
    data class Success(val releaseId: String, val downloadUrl: String, val consoleUrl: String) : UploadResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : UploadResult()
}

// ── Build Progress ─────────────────────────────────────────────────────────

enum class BuildPhase { IDLE, VALIDATING, BUILDING, BUILD_COMPLETE, UPLOADING, UPLOAD_COMPLETE, FAILED }

// ── Recent Deployment ──────────────────────────────────────────────────────

data class RecentDeployment(
    val timestamp: Long,
    val flavor: String?,
    val buildType: String,
    val releaseNotes: String,
    val firebaseConsoleUrl: String
)
