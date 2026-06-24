package com.firebasebuilduploader.services

import com.firebasebuilduploader.model.AndroidProjectConfig
import com.firebasebuilduploader.model.FlavorConfig
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File

@Service(Service.Level.PROJECT)
class GradleFlavorDetectorService(private val project: Project) {

    private val log = thisLogger()

    // ── Shared regex patterns ──────────────────────────────────────────────

    /** Matches Groovy-DSL block openers:  `  uat {` */
    private val groovyBlockName = Regex("""^\s+(\w+)\s*\{""", RegexOption.MULTILINE)

    /** Matches KTS-DSL named block openers:  `create("uat")`, `named("uat")`, etc. */
    private val ktsBlockName = Regex("""(?:create|named|register)\s*\(\s*["'](\w+)["']\s*\)""")

    /** Matches KTS-DSL buildType references only (no `register`). */
    private val ktsBuildTypeName = Regex("""(?:create|named|getByName)\s*\(\s*["'](\w+)["']\s*\)""")

    private val reservedFlavorWords = setOf(
        "create", "named", "getByName", "maybeCreate",
        "register", "configure", "all", "matching",
        "firebaseAppDistribution", "manifestPlaceholders",
        "buildConfigField", "resValue", "isDefault",
        "dimension", "applicationIdSuffix", "versionNameSuffix"
    )
    private val reservedBuildTypeWords = setOf("create", "named", "getByName", "maybeCreate")

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Reads the tester-group aliases from the `firebaseAppDistribution` block
     * in the project's app/build.gradle(.kts).
     *
     * Supports both:
     *   `groups = "tester1,tester2"` (Groovy)
     *   `groups = "tester1"`         (KTS)
     *
     * Returns trimmed, non-blank alias strings; empty list when absent.
     */
    fun parseTesterGroups(): List<String> {
        val gradleFile = findAppGradleFile(File(project.basePath ?: return emptyList()))
            ?: return emptyList()
        return try {
            val block = extractBlock(gradleFile.readText(), "firebaseAppDistribution")
                ?: return emptyList()
            val raw = Regex("""groups\s*=\s*["']([^"']+)["']""")
                .find(block)?.groupValues?.getOrNull(1)
                ?: return emptyList()
            raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            log.warn("FirebaseCoPilot: parseTesterGroups failed: ${e.message}")
            emptyList()
        }
    }

    fun detectProjectConfig(): AndroidProjectConfig {
        val projectDir = File(project.basePath ?: return emptyConfig())

        var config = findAppGradleFile(projectDir)
            ?.let { gradleFile ->
                log.info("FirebaseCoPilot: Parsing $gradleFile")
                runCatching { parseGradleContent(gradleFile.readText(), project.name) }
                    .onFailure { log.warn("FirebaseCoPilot: Failed to parse gradle file: ${it.message}", it) }
                    .getOrNull()
            }
            ?: emptyConfig()

        // Fallback: if no flavors were auto-detected, check for JSON override file
        if (!config.hasFlavors) {
            config = tryLoadFlavorJson(projectDir, config)
        }

        return config
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun tryLoadFlavorJson(projectDir: File, base: AndroidProjectConfig): AndroidProjectConfig {
        val jsonFile = File(projectDir, "firebasecopilot-flavors.json")
        if (!jsonFile.exists()) return base
        return try {
            log.info("FirebaseCoPilot: Found JSON fallback at $jsonFile")
            val jsonObj = JsonParser.parseString(jsonFile.readText()).asJsonObject
            val fallbackFlavors = jsonObj.getAsJsonArray("flavors")
                ?.map { FlavorConfig(name = it.asString) }
                ?.takeIf { it.isNotEmpty() }
                ?: return base
            base.copy(hasFlavors = true, flavors = fallbackFlavors)
        } catch (e: Exception) {
            log.warn("FirebaseCoPilot: Failed to parse firebasecopilot-flavors.json", e)
            base
        }
    }

    private fun findAppGradleFile(projectDir: File): File? {
        val directCandidates = listOf(
            "app/build.gradle.kts",
            "app/build.gradle",
            "build.gradle.kts",
            "build.gradle"
        )
        directCandidates.forEach { rel ->
            val f = File(projectDir, rel)
            if (f.exists() && isAndroidGradleFile(f)) {
                log.info("FirebaseCoPilot: Found gradle file at $f")
                return f
            }
        }
        // Search one level deep in subdirectories
        projectDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.forEach { dir ->
                listOf("build.gradle.kts", "build.gradle").forEach { name ->
                    val f = File(dir, name)
                    if (f.exists() && isAndroidGradleFile(f)) {
                        log.info("FirebaseCoPilot: Found gradle file at $f")
                        return f
                    }
                }
            }
        log.warn("FirebaseCoPilot: No Android gradle file found in $projectDir")
        return null
    }

    private fun isAndroidGradleFile(file: File): Boolean = runCatching {
        val text = file.readText(Charsets.UTF_8)
        text.contains("com.android.application") ||
                text.contains("com.android.library") ||
                (text.contains("android {") && text.contains("compileSdk"))
    }.getOrDefault(false)

    private fun parseGradleContent(content: String, projectName: String): AndroidProjectConfig {
        val flavors    = mutableListOf<FlavorConfig>()
        val buildTypes = mutableListOf<String>()

        // ── productFlavors ─────────────────────────────────────────────────
        extractBlock(content, "productFlavors")?.let { block ->
            log.info("FirebaseCoPilot: Found productFlavors block")
            val seen = mutableSetOf<String>()

            groovyBlockName.findAll(block).forEach { m ->
                val name = m.groupValues[1]
                if (name !in reservedFlavorWords && seen.add(name)) {
                    flavors += FlavorConfig(name = name)
                    log.info("FirebaseCoPilot: Found flavor (groovy): $name")
                }
            }
            ktsBlockName.findAll(block).forEach { m ->
                val name = m.groupValues[1]
                if (seen.add(name)) {
                    flavors += FlavorConfig(name = name)
                    log.info("FirebaseCoPilot: Found flavor (kts): $name")
                }
            }
        }

        // ── buildTypes ────────────────────────────────────────────────────
        extractBlock(content, "buildTypes")?.let { block ->
            val seen = mutableSetOf<String>()

            groovyBlockName.findAll(block).forEach { m ->
                val name = m.groupValues[1]
                if (name !in reservedBuildTypeWords && seen.add(name)) buildTypes += name
            }
            ktsBuildTypeName.findAll(block).forEach { m ->
                val name = m.groupValues[1]
                if (seen.add(name)) buildTypes += name
            }
        }

        if ("debug"   !in buildTypes) buildTypes.add(0, "debug")
        if ("release" !in buildTypes) buildTypes.add("release")

        log.info("FirebaseCoPilot: Result — flavors=$flavors buildTypes=$buildTypes")
        return AndroidProjectConfig(
            projectName = projectName,
            hasFlavors  = flavors.isNotEmpty(),
            flavors     = flavors,
            buildTypes  = buildTypes.distinct()
        )
    }

    /** Brace-matched block extractor — handles nested braces correctly. */
    private fun extractBlock(content: String, blockName: String): String? {
        val match = Regex("""\b$blockName\s*\{""").find(content) ?: return null
        val startBrace = content.indexOf('{', match.range.first)
        if (startBrace < 0) return null
        var depth = 0
        var i = startBrace
        while (i < content.length) {
            when (content[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return content.substring(startBrace, i + 1) }
            }
            i++
        }
        return null
    }

    private fun emptyConfig() = AndroidProjectConfig(
        projectName = project.name,
        hasFlavors  = false,
        buildTypes  = listOf("debug", "release")
    )
}