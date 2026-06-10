package com.firebasebuilduploader.services

import com.firebasebuilduploader.model.FirebaseServiceAccount
import com.firebasebuilduploader.model.UploadResult
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class FirebaseDistributionService(private val project: Project) {

    private val log  = thisLogger()
    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(60,  TimeUnit.SECONDS)
        .readTimeout(300,    TimeUnit.SECONDS)
        .writeTimeout(300,   TimeUnit.SECONDS)
        .callTimeout(600,    TimeUnit.SECONDS)
        // Force HTTP/1.1 — HTTP/2 stream resets (INTERNAL_ERROR) on large APK uploads
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val FIREBASE_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        private const val UPLOAD_BASE    = "https://firebaseappdistribution.googleapis.com/upload/v1"
        private const val API_BASE       = "https://firebaseappdistribution.googleapis.com/v1"
    }

    // ── Internal data class carries the full release info from the operation ──

    private data class ReleaseInfo(
        val releaseId:          String,  // short ID, e.g. "26t142rlb9s9o"
        val releaseName:        String,  // full resource name: "projects/.../releases/..."
        val firebaseConsoleUri: String,  // ready-to-use console URL
        val binaryDownloadUri:  String = "" // direct APK download link (fetched after upload)
    )

    // =========================================================================
    // Public API
    // =========================================================================

    fun parseServiceAccount(jsonPath: String): Result<FirebaseServiceAccount> = runCatching {
        val json = JsonParser.parseString(File(jsonPath).readText()).asJsonObject
        FirebaseServiceAccount(
            type         = json.get("type").asString,
            projectId    = json.get("project_id").asString,
            privateKeyId = json.get("private_key_id").asString,
            privateKey   = json.get("private_key").asString,
            clientEmail  = json.get("client_email").asString,
            clientId     = json.get("client_id").asString,
            tokenUri     = json.get("token_uri")?.asString ?: "https://oauth2.googleapis.com/token"
        )
    }

    /**
     * Resolves the Firebase App ID from the project's Android apps list.
     * Falls back to projectId if the API call fails.
     */
    suspend fun resolveAppId(
        serviceAccountPath: String,
        packageName: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val sa    = parseServiceAccount(serviceAccountPath).getOrThrow()
            val token = acquireAccessToken(serviceAccountPath)
            val req   = Request.Builder()
                .url("https://firebase.googleapis.com/v1beta1/projects/${sa.projectId}/androidApps")
                .addHeader("Authorization", "Bearer $token")
                .get().build()

            val resp  = http.newCall(req).execute()
            val body  = resp.body?.string() ?: return@withContext sa.projectId
            val json  = JsonParser.parseString(body).asJsonObject
            val apps  = json.getAsJsonArray("apps") ?: return@withContext sa.projectId

            val match = if (!packageName.isNullOrBlank()) {
                apps.firstOrNull {
                    it.asJsonObject.get("packageName")?.asString?.equals(packageName, true) == true
                } ?: apps.firstOrNull()
            } else {
                apps.firstOrNull()
            }
            match?.asJsonObject?.get("appId")?.asString ?: sa.projectId
        } catch (e: Exception) {
            log.warn("FirebaseCoPilot: Could not resolve App ID: ${e.message}")
            parseServiceAccount(serviceAccountPath).getOrNull()?.projectId ?: ""
        }
    }

    /**
     * Uploads an APK/AAB to Firebase App Distribution and attaches release notes.
     *
     * Flow:
     *   1. Binary upload via Google resumable upload protocol
     *   2. Poll the long-running operation until done
     *   3. PATCH release notes using the full resource name from the operation response
     *   4. Return the firebaseConsoleUri directly from Firebase (never reconstruct it)
     */
    suspend fun uploadBuild(
        appId:              String,
        apkFile:            File,
        releaseNotes:       String,
        serviceAccountPath: String,
        testerGroups:       List<String> = emptyList(), // groups from firebaseAppDistribution { groups = "..." }
        onProgress:         (String) -> Unit = {}
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            onProgress("Authenticating with Firebase…")
            val token = acquireAccessToken(serviceAccountPath)

            onProgress("Uploading ${apkFile.name} (${apkFile.length() / 1024} KB)…")
            val uploadJson = uploadBinary(appId, apkFile, token, serviceAccountPath)
            val opName     = uploadJson.get("name").asString

            onProgress("Processing upload on Firebase servers…")
            val releaseInfo = pollOperation(opName, token)

            if (releaseNotes.isNotBlank()) {
                onProgress("Attaching release notes…")
                try {
                    patchReleaseNotes(releaseInfo.releaseName, releaseNotes, token)
                } catch (ex: Exception) {
                    log.warn("FirebaseCoPilot: Failed updating notes, binary is safe: ${ex.message}")
                }
            }

            // ── Fix 3: Auto-distribute to tester groups read from build.gradle ──
            val activeGroups = testerGroups.filter { it.isNotBlank() }
            if (activeGroups.isNotEmpty()) {
                onProgress("Distributing to tester group(s): ${activeGroups.joinToString()}…")
                try {
                    distributeRelease(releaseInfo.releaseName, activeGroups, token)
                    log.info("FirebaseCoPilot: Distributed to groups: $activeGroups")
                } catch (ex: Exception) {
                    // Non-fatal — the build is already uploaded; log and carry on
                    log.warn("FirebaseCoPilot: distributeRelease failed (non-fatal): ${ex.message}")
                    onProgress("⚠️ Tester distribution failed: ${ex.message}")
                }
            }

            // Fix 1: Build the proper tester-facing URL (matches what Firebase console shows)
            val downloadUri = buildTesterUrl(appId, releaseInfo.releaseId)

            val consoleUrl = releaseInfo.firebaseConsoleUri.ifBlank {
                val projectId = parseServiceAccount(serviceAccountPath).getOrNull()?.projectId ?: "_"
                "https://console.firebase.google.com/project/$projectId/appdistribution"
            }

            log.info("FirebaseCoPilot: Upload complete — releaseId=${releaseInfo.releaseId}  consoleUrl=$consoleUrl  downloadUri=$downloadUri")

            UploadResult.Success(
                releaseId   = releaseInfo.releaseId,
                downloadUrl = downloadUri.ifBlank { consoleUrl }, // direct link when available
                consoleUrl  = consoleUrl
            )
        } catch (e: Exception) {
            log.error("FirebaseCoPilot: Upload failed", e)
            UploadResult.Failure(reason = e.message ?: "Unknown error", cause = e)
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private fun acquireAccessToken(serviceAccountPath: String): String {
        val creds = ServiceAccountCredentials
            .fromStream(File(serviceAccountPath).inputStream())
            .createScoped(listOf(FIREBASE_SCOPE))
        creds.refreshIfExpired()
        return creds.accessToken.tokenValue
    }

    /**
     * Uploads the APK using Google's resumable upload protocol.
     * Retries up to 3 times with exponential back-off.
     */
    private fun uploadBinary(
        appId:              String,
        apkFile:            File,
        token:              String,
        serviceAccountPath: String
    ): JsonObject {
        val mime = if (apkFile.extension.equals("aab", true))
            "application/octet-stream"
        else
            "application/vnd.android.package-archive"

        // App IDs are formatted as  1:PROJECT_NUMBER:android:HEX
        val projectNumber = appId.split(":").getOrNull(1)
            ?: parseServiceAccount(serviceAccountPath).getOrNull()?.projectId
            ?: throw IllegalStateException("Could not parse project number from App ID: $appId")

        val uploadUrl  = "$UPLOAD_BASE/projects/$projectNumber/apps/$appId/releases:upload"
        val fileLength = apkFile.length()

        log.info("FirebaseCoPilot: Resumable upload — appId=$appId  size=${fileLength / 1024} KB")

        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt > 0) {
                    log.warn("FirebaseCoPilot: Retry ${attempt + 1}: ${lastError?.message}")
                    Thread.sleep(4_000L * attempt)
                }

                // Step A: Initiate resumable session
                val initReq = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization",                        "Bearer $token")
                    .addHeader("X-Goog-Upload-Protocol",               "resumable")
                    .addHeader("X-Goog-Upload-Command",                "start")
                    .addHeader("X-Goog-Upload-Header-Content-Length",  fileLength.toString())
                    .addHeader("X-Goog-Upload-Header-Content-Type",    mime)
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()

                val initResp = http.newCall(initReq).execute()
                val initBody = initResp.body?.string() ?: ""
                if (!initResp.isSuccessful) {
                    val reason = if (initResp.code == 403)
                        "Permission denied (403) — ensure the service account has the " +
                                "'Firebase App Distribution Admin' role in Google Cloud IAM."
                    else
                        "Upload session init failed (${initResp.code}): $initBody"
                    throw IllegalStateException(reason)
                }

                val sessionUrl = initResp.header("X-Goog-Upload-URL")
                    ?: throw IllegalStateException("Firebase did not return X-Goog-Upload-URL")

                log.info("FirebaseCoPilot: Session URL obtained, streaming binary…")

                // Step B: Stream the file
                val fileBody = object : okhttp3.RequestBody() {
                    override fun contentType()  = mime.toMediaType()
                    override fun contentLength() = fileLength
                    override fun writeTo(sink: BufferedSink) {
                        apkFile.inputStream().use { input ->
                            val buf = ByteArray(512 * 1024)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                sink.write(buf, 0, read)
                            }
                        }
                    }
                }

                val uploadReq = Request.Builder()
                    .url(sessionUrl)
                    .addHeader("Authorization",          "Bearer $token")
                    .addHeader("X-Goog-Upload-Command",  "upload, finalize")
                    .addHeader("X-Goog-Upload-Offset",   "0")
                    .post(fileBody)
                    .build()

                val uploadResp     = http.newCall(uploadReq).execute()
                val uploadRespBody = uploadResp.body?.string()
                    ?: throw IllegalStateException("Empty response from upload endpoint")

                log.info("FirebaseCoPilot: Upload response ${uploadResp.code}: ${uploadRespBody.take(200)}")

                if (!uploadResp.isSuccessful) {
                    throw IllegalStateException("Upload failed (${uploadResp.code}): $uploadRespBody")
                }

                return@uploadBinary JsonParser.parseString(uploadRespBody).asJsonObject

            } catch (e: Exception) {
                lastError = e
                log.warn("FirebaseCoPilot: Upload attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Upload failed after 3 attempts")
    }

    /**
     * Polls the Firebase Long-Running Operation until done.
     *
     * FIX: Returns full ReleaseInfo (releaseId + releaseName + firebaseConsoleUri)
     * instead of just the releaseId string.
     *
     * The operation response structure is:
     * {
     *   "done": true,
     *   "response": {
     *     "@type": "...UploadReleaseResponse",
     *     "result": "RELEASE_CREATED" | "RELEASE_UNMODIFIED",
     *     "release": {
     *       "name": "projects/NUMBER/apps/APP_ID/releases/RELEASE_ID",
     *       "firebaseConsoleUri": "https://console.firebase.google.com/..."  ← correct URL
     *     }
     *   }
     * }
     */
    private fun pollOperation(operationName: String, token: String): ReleaseInfo {
        repeat(30) {
            Thread.sleep(3_000)

            val req = Request.Builder()
                .url("$API_BASE/$operationName")
                .addHeader("Authorization", "Bearer $token")
                .get().build()

            val rawBody = http.newCall(req).execute().body?.string() ?: "{}"
            log.info("FirebaseCoPilot: pollOperation response: ${rawBody.take(600)}")

            val json = JsonParser.parseString(rawBody).asJsonObject

            // Surface Firebase-side errors immediately
            if (json.has("error")) {
                val errMsg = json.getAsJsonObject("error")?.get("message")?.asString ?: "Unknown"
                throw IllegalStateException("Firebase operation error: $errMsg")
            }

            if (json.get("done")?.asBoolean == true) {
                val releaseObj = json.getAsJsonObject("response")
                    ?.getAsJsonObject("release")
                    ?: throw IllegalStateException(
                        "Operation done but 'response.release' missing. Full: $rawBody"
                    )

                // Full resource name: "projects/NUMBER/apps/APP_ID/releases/RELEASE_ID"
                val releaseName = releaseObj.get("name")?.asString
                    ?: throw IllegalStateException("'release.name' missing. Full: $rawBody")

                val releaseId = releaseName.substringAfterLast("/")
                if (releaseId.isBlank()) {
                    throw IllegalStateException("Blank release ID from releaseName='$releaseName'")
                }

                // Firebase provides the correct console URL with package name — use it directly
                val consoleUri = releaseObj.get("firebaseConsoleUri")?.asString ?: ""

                log.info("FirebaseCoPilot: Release resolved — id=$releaseId  consoleUri=$consoleUri")
                return ReleaseInfo(
                    releaseId       = releaseId,
                    releaseName     = releaseName,
                    firebaseConsoleUri = consoleUri
                )
            }
        }
        throw IllegalStateException("Upload timed out after 90 s")
    }


    /**
     * Patches the release notes for an existing release.
     * Takes the full releaseName resource path directly from the operation response.
     */
    private fun patchReleaseNotes(releaseName: String, notes: String, token: String) {
        if (releaseName.isBlank()) {
            log.warn("FirebaseCoPilot: patchReleaseNotes skipped — releaseName is blank")
            return
        }
        val url  = "$API_BASE/$releaseName?updateMask=release_notes"
        val body = """{"releaseNotes":{"text":${gson.toJson(notes)}}}"""
        log.info("FirebaseCoPilot: PATCH release notes — url=$url")

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization",  "Bearer $token")
            .addHeader("Content-Type",   "application/json")
            .patch(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp     = http.newCall(req).execute()
        val respBody = resp.body?.string()
        if (!resp.isSuccessful) {
            log.warn("FirebaseCoPilot: PATCH release notes FAILED (${resp.code}) — $respBody")
        } else {
            log.info("FirebaseCoPilot: PATCH release notes OK — ${respBody?.take(200)}")
        }
    }

    /**
     * Fix 1: Constructs the tester-facing Firebase App Distribution URL.
     *
     * Format matches what the Firebase console shows when you copy the link:
     *   https://appdistribution.firebase.google.com/testerapps/{appId}/releases/{releaseId}
     *
     * This is the shareable URL testers use to install the build — not the raw
     * signed binary download URL (binaryDownloadUri) which is internal/ephemeral.
     */
    private fun buildTesterUrl(appId: String, releaseId: String): String =
        "https://appdistribution.firebase.google.com/testerapps/$appId/releases/$releaseId"

    /**
     * Fix 3: Distributes a release to the specified tester groups.
     *
     * POST projects/{project}/apps/{app}/releases/{release}:distribute
     * Body: { "groupAliases": ["group1", "group2"] }
     *
     * The group aliases come from the `groups` property inside
     * firebaseAppDistribution { } in the project's build.gradle.
     */
    private fun distributeRelease(releaseName: String, groups: List<String>, token: String) {
        if (groups.isEmpty()) return

        val url  = "$API_BASE/$releaseName:distribute"
        val body = gson.toJson(mapOf("groupAliases" to groups))
        log.info("FirebaseCoPilot: distributeRelease — url=$url  groups=$groups")

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type",  "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp     = http.newCall(req).execute()
        val respBody = resp.body?.string()
        if (!resp.isSuccessful) {
            throw IllegalStateException("distributeRelease failed (${resp.code}): $respBody")
        }
        log.info("FirebaseCoPilot: distributeRelease OK — ${respBody?.take(200)}")
    }
}