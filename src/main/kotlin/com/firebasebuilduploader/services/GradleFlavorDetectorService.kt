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

    /**
     * Fix 3: Reads the tester group aliases from the firebaseAppDistribution block
     * in the project's app/build.gradle(.kts).
     *
     * Supports both:
     *   groups = "tester1,tester2"        (Groovy / string assignment)
     *   groups = "tester1"
     *
     * Returns a list of trimmed, non-blank alias strings, e.g. ["tester1", "tester2"].
     * Returns an empty list when the block or property is absent.
     */
    fun parseTesterGroups(): List<String> {
        val projectDir = File(project.basePath ?: return emptyList())
        val gradleFile = findAppGradleFile(projectDir) ?: return emptyList()
        return try {
            val content = gradleFile.readText()
            val block   = extractBlock(content, "firebaseAppDistribution") ?: return emptyList()
            // Match:  groups = "tester1,tester2"  or  groups = 'tester1'
            val match   = Regex("""groups\s*=\s*["']([^"']+)["']""").find(block)
            val raw     = match?.groupValues?.getOrNull(1) ?: return emptyList()
            raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            log.warn("FirebaseCoPilot: parseTesterGroups failed: ${e.message}")
            emptyList()
        }
    }

    fun detectProjectConfig(): AndroidProjectConfig {
        val projectDir = File(project.basePath ?: return emptyConfig())

        // 1. First priority: Try to auto-detect from Gradle
        val gradleFile = findAppGradleFile(projectDir)
        var config = if (gradleFile != null) {
            log.info("FirebaseCoPilot: Parsing $gradleFile")
            try {
                parseGradleContent(gradleFile.readText(), project.name)
            } catch (e: Exception) {
                log.warn("FirebaseCoPilot: Failed to parse gradle file: ${e.message}", e)
                emptyConfig()
            }
        } else {
            emptyConfig()
        }

        // 2. Fallback: If no flavors were auto-detected, check for JSON file
        if (!config.hasFlavors) {
            val jsonFallback = File(projectDir, "firebasecopilot-flavors.json")
            if (jsonFallback.exists()) {
                try {
                    log.info("FirebaseCoPilot: Found JSON fallback at $jsonFallback")
                    val jsonText = jsonFallback.readText()
                    val jsonObj = JsonParser.parseString(jsonText).asJsonObject
                    if (jsonObj.has("flavors")) {
                        val fallbackFlavors = jsonObj.getAsJsonArray("flavors").map {
                            FlavorConfig(name = it.asString)
                        }
                        if (fallbackFlavors.isNotEmpty()) {
                            config = config.copy(
                                hasFlavors = true,
                                flavors = fallbackFlavors
                            )
                        }
                    }
                } catch (e: Exception) {
                    log.warn("FirebaseCoPilot: Failed to parse firebasecopilot-flavors.json", e)
                }
            }
        }

        return config
    }

    private fun findAppGradleFile(projectDir: File): File? {
        // Priority: app module gradle first
        val direct = listOf(
            "app/build.gradle.kts",
            "app/build.gradle",
            "build.gradle.kts",
            "build.gradle"
        )
        for (rel in direct) {
            val f = File(projectDir, rel)
            if (f.exists() && isAndroidGradleFile(f)) {
                log.info("FirebaseCoPilot: Found gradle file at $f")
                return f
            }
        }
        // Search all subdirectories one level deep
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

    private fun isAndroidGradleFile(file: File): Boolean {
        return try {
            val text = file.readText(Charsets.UTF_8)
            text.contains("com.android.application") ||
                    text.contains("com.android.library") ||
                    (text.contains("android {") && text.contains("compileSdk"))
        } catch (e: Exception) { false }
    }

    private fun parseGradleContent(content: String, projectName: String): AndroidProjectConfig {
        val flavors    = mutableListOf<FlavorConfig>()
        val buildTypes = mutableListOf<String>()

        // ── productFlavors block ───────────────────────────────────────────
        val flavorsBlock = extractBlock(content, "productFlavors")
        if (flavorsBlock != null) {
            log.info("FirebaseCoPilot: Found productFlavors block")
            // Groovy DSL:  uat { ... }
            // KTS DSL:     create("uat") { ... }  or  named("uat") { ... }
            val groovyPattern = Regex("""^\s+(\w+)\s*\{""", RegexOption.MULTILINE)
            // Update this line inside parseGradleContent:
            val ktsPattern    = Regex("""(?:create|named|register)\s*\(\s*["'](\w+)["']\s*\)""")

            val seen = mutableSetOf<String>()
            val reserved = setOf(
                "create", "named", "getByName", "maybeCreate",
                "register", "configure", "all", "matching",
                "firebaseAppDistribution", "manifestPlaceholders",
                "buildConfigField", "resValue", "isDefault",
                "dimension", "applicationIdSuffix", "versionNameSuffix"
            )

            groovyPattern.findAll(flavorsBlock).forEach { m ->
                val name = m.groupValues[1]
                if (name !in reserved && seen.add(name)) {
                    flavors += FlavorConfig(name = name)
                    log.info("FirebaseCoPilot: Found flavor (groovy): $name")
                }
            }
            ktsPattern.findAll(flavorsBlock).forEach { m ->
                val name = m.groupValues[1]
                if (seen.add(name)) {
                    flavors += FlavorConfig(name = name)
                    log.info("FirebaseCoPilot: Found flavor (kts): $name")
                }
            }
        }

        // ── buildTypes block ───────────────────────────────────────────────
        val buildTypesBlock = extractBlock(content, "buildTypes")
        if (buildTypesBlock != null) {
            val groovyBT = Regex("""^\s+(\w+)\s*\{""", RegexOption.MULTILINE)
            val ktsBT    = Regex("""(?:create|named|getByName)\s*\(\s*["'](\w+)["']\s*\)""")
            val reservedBT = setOf("create", "named", "getByName", "maybeCreate")
            val seenBT = mutableSetOf<String>()

            groovyBT.findAll(buildTypesBlock).forEach { m ->
                val name = m.groupValues[1]
                if (name !in reservedBT && seenBT.add(name)) buildTypes += name
            }
            ktsBT.findAll(buildTypesBlock).forEach { m ->
                val name = m.groupValues[1]
                if (seenBT.add(name)) buildTypes += name
            }
        }

        if ("debug"   !in buildTypes) buildTypes.add(0, "debug")
        if ("release" !in buildTypes) buildTypes.add("release")

        log.info("FirebaseCoPilot: Result — flavors=$flavors buildTypes=$buildTypes")
        return AndroidProjectConfig(
            projectName      = projectName,
            hasFlavors       = flavors.isNotEmpty(),
            flavors          = flavors,
            buildTypes       = buildTypes.distinct()
        )
    }

    /** Brace-matched block extractor — handles nested braces correctly. */
    /** Brace-matched block extractor — handles nested braces correctly. */
    private fun extractBlock(content: String, blockName: String): String? {
        // Relaxed regex: just looks for the word followed by { anywhere (handles indents)
        val regex = Regex("""\b$blockName\s*\{""")
        val match = regex.find(content) ?: return null
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