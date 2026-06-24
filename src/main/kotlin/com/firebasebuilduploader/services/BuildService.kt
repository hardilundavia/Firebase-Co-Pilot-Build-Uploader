package com.firebasebuilduploader.services

import com.firebasebuilduploader.model.BuildConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Service(Service.Level.PROJECT)
class BuildService(private val project: Project) {

    private val log = thisLogger()

    /**
     * Assembles an APK for the given [config].
     *
     * When [hasFirebasePlugin] is true, the Firebase App Distribution Gradle
     * task is excluded via `-x` to prevent double-uploads. Passing `-x` for a
     * task that does not exist causes Gradle to abort with "Task not found", so
     * the flag is only applied when the plugin is confirmed present.
     *
     * Use [forDeploy] = true when the APK will be uploaded immediately after
     * the build (currently identical behaviour, kept as a semantic hint and for
     * future differentiation such as notarisation or signing checks).
     */
    suspend fun assembleBuild(
        config:            BuildConfiguration,
        hasFirebasePlugin: Boolean = false,
        forDeploy:         Boolean = false,
        onOutput:          (String) -> Unit = {}
    ): File? = runGradleTask(
        config    = config,
        extraArgs = if (hasFirebasePlugin) "-x ${config.firebaseUploadTaskName()}" else "",
        onOutput  = onOutput
    )

    // ── Core Gradle runner ─────────────────────────────────────────────────

    private suspend fun runGradleTask(
        config:    BuildConfiguration,
        extraArgs: String = "",
        onOutput:  (String) -> Unit = {}
    ): File? = suspendCancellableCoroutine { continuation ->

        val projectPath = project.basePath ?: run {
            continuation.resumeWithException(IllegalStateException("No project path"))
            return@suspendCancellableCoroutine
        }

        val taskName = config.gradleAssembleTask()

        onOutput("─────────────────────────────────────")
        onOutput("🏗  Flavor    : ${config.flavor ?: "none"}")
        onOutput("🏗  BuildType : ${config.buildType}")
        onOutput("▶  Running :app:$taskName…")
        log.info("FirebaseCoPilot: Executing :app:$taskName  flavor=${config.flavor}  buildType=${config.buildType}  extraArgs='$extraArgs'")

        val scriptParams = listOf("--info", extraArgs)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
            externalProjectPath    = projectPath
            taskNames              = listOf(":app:$taskName")
            vmOptions              = ""
            scriptParameters       = scriptParams
        }

        val callback = object : TaskCallback {
            override fun onSuccess() {
                onOutput("✅ Build succeeded")
                continuation.resume(findApk(projectPath, config, onOutput))
            }
            override fun onFailure() {
                onOutput("❌ Build failed — check Build Output tab")
                continuation.resumeWithException(
                    RuntimeException("Gradle task :app:$taskName failed")
                )
            }
        }

        ExternalSystemUtil.runTask(
            settings,
            DefaultRunExecutor.EXECUTOR_ID,
            project,
            GradleConstants.SYSTEM_ID,
            callback,
            ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            false
        )
    }

    // ── APK Discovery ──────────────────────────────────────────────────────

    fun findApk(
        projectPath: String,
        config:      BuildConfiguration,
        onOutput:    (String) -> Unit = {}
    ): File? {
        val flavor    = config.flavor?.lowercase()
        val buildType = config.buildType.lowercase()
        val mod       = "app"

        // 1. Try well-known exact paths first
        val candidates = if (flavor != null) listOf(
            "$mod/build/outputs/apk/$flavor/$buildType/$mod-$flavor-$buildType.apk",
            "$mod/build/outputs/apk/$flavor/$buildType/$mod-$flavor-$buildType-unsigned.apk",
            "$mod/build/outputs/apk/$flavor/$buildType/$mod-$buildType.apk",
            "$mod/build/outputs/apk/$flavor/$buildType/$mod-$buildType-unsigned.apk"
        ) else listOf(
            "$mod/build/outputs/apk/$buildType/$mod-$buildType.apk",
            "$mod/build/outputs/apk/$buildType/$mod-$buildType-unsigned.apk"
        )

        candidates.forEach { rel ->
            val f = File(projectPath, rel)
            if (f.exists() && f.extension == "apk") {
                log.info("FirebaseCoPilot: Found APK (exact) at $f")
                onOutput("📦 APK: ${f.absolutePath}")
                return f
            }
        }

        // 2. Walk only the correct flavor/buildType subdirectory — never pick
        //    a stale APK from a different flavor (e.g. prod/debug for uat/release).
        val scopedDir = if (flavor != null) {
            File(projectPath, "$mod/build/outputs/apk/$flavor/$buildType")
        } else {
            File(projectPath, "$mod/build/outputs/apk/$buildType")
        }

        log.info("FirebaseCoPilot: Exact candidates missed — walking: $scopedDir")
        onOutput("🔍 Searching in: ${scopedDir.path}")

        if (scopedDir.exists() && scopedDir.isDirectory) {
            val signed = scopedDir.walkTopDown()
                .filter { it.isFile && it.extension == "apk" && !it.name.contains("unsigned") }
                .maxByOrNull { it.lastModified() }
            if (signed != null) {
                log.info("FirebaseCoPilot: Found APK (scoped) at $signed")
                onOutput("📦 APK: ${signed.absolutePath}")
                return signed
            }
            val unsigned = scopedDir.walkTopDown()
                .filter { it.isFile && it.extension == "apk" }
                .maxByOrNull { it.lastModified() }
            if (unsigned != null) {
                log.info("FirebaseCoPilot: Found APK (unsigned fallback) at $unsigned")
                onOutput("📦 APK: ${unsigned.absolutePath.replace("-unsigned.apk", ".apk")}")
                return unsigned
            }
        }

        // 3. Diagnostic only — never silently pick wrong flavor
        val outputsRoot = File(projectPath, "$mod/build/outputs/apk")
        if (outputsRoot.exists()) {
            val allApks = outputsRoot.walkTopDown()
                .filter { it.isFile && it.extension == "apk" }
                .sortedByDescending { it.lastModified() }
                .take(5).toList()
            if (allApks.isNotEmpty()) {
                onOutput("⚠️  No APK in expected dir: ${scopedDir.path}")
                onOutput("   Existing APKs (NOT used):")
                allApks.forEach { onOutput("   • ${it.relativeTo(File(projectPath))}") }
                onOutput("   Verify flavor/buildType names match your build.gradle exactly.")
            } else {
                onOutput("❌ No APKs found under $outputsRoot")
            }
        }

        log.warn("FirebaseCoPilot: APK not found for flavor=$flavor buildType=$buildType in $scopedDir")
        return null
    }
}