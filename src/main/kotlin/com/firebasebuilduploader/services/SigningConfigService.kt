package com.firebasebuilduploader.services

import com.firebasebuilduploader.model.SigningConfigData
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

@Service(Service.Level.PROJECT)
class SigningConfigService(private val project: Project) {

    private val log = thisLogger()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns true if app/build.gradle(.kts) already has a signingConfigs block
     * AND the release buildType references it.
     */
    fun hasReleaseSigningConfig(): Boolean {
        val gradleFile = findAppGradleFile() ?: return false
        return try {
            val content = gradleFile.readText()
            val hasBlock = Regex("""signingConfigs\s*\{""").containsMatchIn(content)
            val releaseRef = extractBuildTypeBlock(content, "release")?.let {
                Regex("""signingConfig\s*=?\s*signingConfigs\.\w+""").containsMatchIn(it)
            } ?: false
            hasBlock && releaseRef
        } catch (e: Exception) {
            log.warn("FirebaseCoPilot: hasReleaseSigningConfig check failed: ${e.message}")
            false
        }
    }

    /**
     * PUBLIC: generates a new .jks keystore via keytool only.
     * Does NOT touch build.gradle. Used by CreateKeystoreDialog.
     *
     * Returns null on success, or an error message string on failure.
     */
    fun generateKeystoreOnly(data: SigningConfigData): String? =
        generateKeystore(data)

    /**
     * Full pipeline:
     *  1. Optionally generate a new .jks via keytool
     *  2. Write keystore.properties with the signing values
     *  3. Inject a Gradle signingConfigs block that reads from keystore.properties
     *  4. Trigger a Gradle sync
     *
     * Returns null on success, or an error message string on failure.
     */
    fun applySigningConfig(data: SigningConfigData, onSyncStarted: () -> Unit = {}): String? {
        val gradleFile = findAppGradleFile()
            ?: return "Could not locate app/build.gradle(.kts) in this project."

        return try {
            if (data.isNewKeystore) {
                val err = generateKeystore(data)
                if (err != null) return err
            } else {
                val ksFile = File(data.keystorePath)
                if (!ksFile.exists()) return "Selected keystore file does not exist: ${data.keystorePath}"
            }

            writeKeystoreProperties(data)
            injectSigningConfig(gradleFile, data)
            updateGitignore(data)

            triggerGradleSync()
            onSyncStarted()
            null
        } catch (e: Exception) {
            log.warn("FirebaseCoPilot: applySigningConfig failed: ${e.message}", e)
            "Failed to apply signing configuration: ${e.message}"
        }
    }

    // ── Keystore generation via keytool ─────────────────────────────────────

    private fun generateKeystore(data: SigningConfigData): String? {
        val ksFile = File(data.keystorePath)
        ksFile.parentFile?.mkdirs()
        if (ksFile.exists()) ksFile.delete()

        val dn = buildString {
            append("CN=${escapeDn(data.firstAndLastName)}")
            if (data.organizationalUnit.isNotBlank()) append(", OU=${escapeDn(data.organizationalUnit)}")
            if (data.organization.isNotBlank())       append(", O=${escapeDn(data.organization)}")
            if (data.city.isNotBlank())               append(", L=${escapeDn(data.city)}")
            if (data.state.isNotBlank())              append(", ST=${escapeDn(data.state)}")
            if (data.countryCode.isNotBlank())        append(", C=${escapeDn(data.countryCode)}")
        }

        val javaHome    = System.getProperty("java.home")
        val keytoolBin  = File(javaHome, "bin/keytool" + if (isWindows()) ".exe" else "")
        val keytool     = if (keytoolBin.exists()) keytoolBin.absolutePath else "keytool"
        val validityDays = data.validityYears * 365

        val cmd = listOf(
            keytool, "-genkeypair",
            "-v",
            "-keystore",  ksFile.absolutePath,
            "-storepass", data.keystorePassword,
            "-alias",     data.keyAlias,
            "-keypass",   data.keyPassword,
            "-keyalg",    "RSA",
            "-keysize",   "2048",
            "-validity",  validityDays.toString(),
            "-dname",     dn,
            "-storetype", "PKCS12"
        )

        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output  = process.inputStream.bufferedReader().readText()
        val exit    = process.waitFor()

        return if (exit != 0 || !ksFile.exists()) {
            log.warn("FirebaseCoPilot: keytool failed (exit=$exit): $output")
            "Keystore generation failed:\n$output"
        } else {
            log.info("FirebaseCoPilot: Generated keystore at ${ksFile.absolutePath}")
            null
        }
    }

    private fun escapeDn(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    // ── keystore.properties writer ──────────────────────────────────────────

    /**
     * Writes keystore.properties into the app module directory.
     * Values are stored here — NOT baked into build.gradle.
     */
    private fun writeKeystoreProperties(data: SigningConfigData) {
        val projectDir = File(project.basePath ?: return)
        val appDir     = findAppModuleDir(projectDir)
        val propsFile  = File(appDir, "keystore.properties")

        val storeFilePath = File(data.keystorePath).canonicalPath.replace('\\', '/')

        propsFile.writeText(
            "# Generated by FirebaseCoPilot — do not commit this file\n" +
                    "storeFile=$storeFilePath\n" +
                    "storePassword=${data.keystorePassword}\n" +
                    "keyAlias=${data.keyAlias}\n" +
                    "keyPassword=${data.keyPassword}\n"
        )
        log.info("FirebaseCoPilot: Wrote keystore.properties to ${propsFile.absolutePath}")
    }

    // ── build.gradle injection ──────────────────────────────────────────────

    /**
     * Injects a signingConfigs block that reads from keystore.properties at
     * Gradle evaluation time. Safe to call multiple times — replaces any
     * previously injected block.
     */
    private fun injectSigningConfig(gradleFile: File, data: SigningConfigData) {
        log.info("FirebaseCoPilot: Injecting signing config into ${gradleFile.absolutePath}")
        var content = gradleFile.readText()
        val isKts   = gradleFile.name.endsWith(".kts")

        val signingConfigsBlock = buildSigningConfigsBlock(isKts)

        content = if (Regex("""signingConfigs\s*\{""").containsMatchIn(content)) {
            replaceBlock(content, "signingConfigs", signingConfigsBlock)
        } else {
            insertIntoAndroidBlock(content, "\n$signingConfigsBlock\n", isKts)
        }

        val signingConfigRef = if (isKts) {
            "            signingConfig = signingConfigs.getByName(\"release\")"
        } else {
            "            signingConfig signingConfigs.release"
        }
        content = ensureReleaseSigningConfigRef(content, signingConfigRef, isKts)

        gradleFile.writeText(content)
        log.info("FirebaseCoPilot: Done — signing config injected successfully")
    }

    /**
     * Builds the signingConfigs block using Properties() (NOT java.util.Properties())
     * to avoid Gradle KTS import issues. The Gradle runtime already has
     * java.util.Properties on the classpath, so the short name works fine.
     */
    private fun buildSigningConfigsBlock(isKts: Boolean): String = if (isKts) {
        """
    signingConfigs {
        create("release") {
            val keystorePropsFile = rootProject.file("app/keystore.properties")
            val keystoreProps = Properties()
            keystoreProps.load(keystorePropsFile.inputStream())
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
            storeType = "PKCS12"
        }
    }"""
    } else {
        """
    signingConfigs {
        release {
            def keystorePropsFile = rootProject.file("app/keystore.properties")
            def keystoreProps = new Properties()
            keystoreProps.load(new FileInputStream(keystorePropsFile))
            storeFile file(keystoreProps['storeFile'])
            storePassword keystoreProps['storePassword']
            keyAlias keystoreProps['keyAlias']
            keyPassword keystoreProps['keyPassword']
            storeType 'PKCS12'
        }
    }"""
    }

    // ── Gradle file manipulation helpers ────────────────────────────────────

    private fun insertIntoAndroidBlock(content: String, block: String, isKts: Boolean): String {
        val androidStart = Regex("""\bandroid\s*\{""").find(content)
            ?: throw IllegalStateException("Could not find android { } block in build.gradle")
        val braceIdx = content.indexOf('{', androidStart.range.first)
        val insertAt = braceIdx + 1
        return content.substring(0, insertAt) + block + content.substring(insertAt)
    }

    private fun ensureReleaseSigningConfigRef(
        content: String,
        signingConfigRef: String,
        isKts: Boolean
    ): String {
        val buildTypesBlock = extractBlock(content, "buildTypes")
            ?: throw IllegalStateException("Could not find buildTypes { } block in build.gradle")

        val releaseBlock = extractBuildTypeBlock(content, "release")

        val existingRefPattern = Regex(
            """[ \t]*if\s*\([^\n]*keystorePropertiesFile[^\n]*\)\s*\{[^}]*signingConfig[^\n]*\n[^\n]*\}\n?""" +
                    """|[ \t]*signingConfig\s*=?\s*signingConfigs\.[^\n]*\n?"""
        )

        return if (releaseBlock != null) {
            val updated = if (existingRefPattern.containsMatchIn(releaseBlock)) {
                releaseBlock.replace(existingRefPattern, "$signingConfigRef\n")
            } else {
                val openBrace = releaseBlock.indexOf('{')
                releaseBlock.substring(0, openBrace + 1) +
                        "\n$signingConfigRef\n" +
                        releaseBlock.substring(openBrace + 1)
            }
            content.replaceFirst(releaseBlock, updated)
        } else {
            val newReleaseBlock = if (isKts) {
                "\n        getByName(\"release\") {\n$signingConfigRef\n        }\n"
            } else {
                "\n        release {\n$signingConfigRef\n        }\n"
            }
            val openBrace = buildTypesBlock.indexOf('{')
            val updatedBuildTypes = buildTypesBlock.substring(0, openBrace + 1) +
                    newReleaseBlock +
                    buildTypesBlock.substring(openBrace + 1)
            content.replaceFirst(buildTypesBlock, updatedBuildTypes)
        }
    }

    private fun replaceBlock(content: String, blockName: String, newBlock: String): String {
        val old = extractBlock(content, blockName) ?: return content
        return content.replaceFirst(old, newBlock)
    }

    private fun extractBuildTypeBlock(content: String, typeName: String): String? {
        val buildTypesBlock = extractBlock(content, "buildTypes") ?: return null
        val patterns = listOf(
            Regex("""\b$typeName\s*\{"""),
            Regex("""(?:getByName|named|create)\s*\(\s*["']$typeName["']\s*\)\s*\{""")
        )
        for (pattern in patterns) {
            val match      = pattern.find(buildTypesBlock) ?: continue
            val startBrace = buildTypesBlock.indexOf('{', match.range.first)
            var depth = 0
            var i = startBrace
            while (i < buildTypesBlock.length) {
                when (buildTypesBlock[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return buildTypesBlock.substring(match.range.first, i + 1) }
                }
                i++
            }
        }
        return null
    }

    private fun extractBlock(content: String, blockName: String): String? {
        val match      = Regex("""\b$blockName\s*\{""").find(content) ?: return null
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

    // ── .gitignore ──────────────────────────────────────────────────────────

    private fun updateGitignore(data: SigningConfigData) {
        val projectDir = File(project.basePath ?: return)
        val gitignore  = File(projectDir, ".gitignore")
        try {
            val existing = if (gitignore.exists()) gitignore.readText() else ""
            val entries  = listOf("keystore.properties", "key.properties", "*.jks", "*.keystore", "*.p12")
            val toAdd    = entries.filter { !existing.contains(it) }
            if (toAdd.isNotEmpty()) {
                gitignore.appendText(
                    (if (existing.isNotBlank() && !existing.endsWith("\n")) "\n" else "") +
                            "\n# Added by FirebaseCoPilot — keystore secrets\n" +
                            toAdd.joinToString("\n") + "\n"
                )
            }
        } catch (e: Exception) {
            log.warn("FirebaseCoPilot: Could not update .gitignore: ${e.message}")
        }
    }

    // ── File discovery ──────────────────────────────────────────────────────

    fun findAppGradleFile(): File? {
        val projectDir = File(project.basePath ?: return null)
        val appDir     = findAppModuleDir(projectDir)
        val kts        = File(appDir, "build.gradle.kts")
        val groovy     = File(appDir, "build.gradle")
        return when {
            kts.exists()    -> kts
            groovy.exists() -> groovy
            else            -> null
        }
    }

    private fun findAppModuleDir(projectDir: File): File {
        val app = File(projectDir, "app")
        return if (app.exists()) app else projectDir
    }

    // ── Gradle sync ─────────────────────────────────────────────────────────

    fun triggerGradleSync() {
        log.info("FirebaseCoPilot: Triggering Gradle sync")
        ExternalSystemUtil.refreshProject(
            project,
            GradleConstants.SYSTEM_ID,
            project.basePath ?: return,
            false,
            com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode.IN_BACKGROUND_ASYNC
        )
    }
}