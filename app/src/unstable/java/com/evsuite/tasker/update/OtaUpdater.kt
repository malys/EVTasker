package com.evsuite.tasker.update

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Over-the-air updater — UNSTABLE BUILDS ONLY.
 *
 * The stable channel deliberately has no self-update path: this class is not in a stable
 * build. Unstable testers get updates without manual work, and accept that channel's risk.
 *
 * Security posture is the one EVProfile settled on in its OTA work:
 *  - the APK URL comes from a remote JSON document and is never trusted: https only,
 *    exact-match host allowlist, re-checked before it reaches the system downloader;
 *  - the downloaded APK must be signed by the same certificate as the running app, or it
 *    is deleted rather than offered for install;
 *  - both checks fail closed.
 */
object OtaUpdater {

    private const val TAG = "OtaUpdater"
    private const val CACHE_PREFIX = "EVTasker-ota-"
    private const val RELEASES_API = "https://api.github.com/repos/malys/EVTasker/releases"
    private const val TIMEOUT_MS = 10_000

    // Hosts an update may come from. The githubusercontent entries are the CDNs GitHub
    // redirects release-asset downloads to; without them the download fails.
    private val ALLOWED_HOSTS = setOf(
        "api.github.com", "github.com",
        "objects.githubusercontent.com", "release-assets.githubusercontent.com"
    )

    data class Update(val versionName: String, val apkUrl: String)

    fun purgeCachedApks(context: Context) {
        context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(CACHE_PREFIX) && file.name.endsWith(".apk")
        }?.forEach { file ->
            if (!file.delete()) Log.w(TAG, "Could not purge cached OTA APK: ${file.name}")
        }
    }

    /**
     * True if [url] is https and points at an allowed host. Rejects http (including an
     * https→http downgrade), unknown hosts, unparsable URLs, and lookalikes such as
     * "github.com.attacker.net" — the host match is exact, never a suffix test.
     */
    fun isAllowedUrl(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (uri.scheme?.equals("https", ignoreCase = true) != true) return false
        val host = uri.host ?: return false
        return host.lowercase(Locale.US) in ALLOWED_HOSTS
    }

    /**
     * Numeric core of a version: "1.0.0.42-unstable" → [1,0,0,42]. A segment with no digits
     * becomes 0 rather than being dropped, so later segments do not shift left.
     */
    fun segments(version: String): IntArray {
        var core = version.removePrefix("v").removePrefix("V")
        core.indexOf('+').let { if (it >= 0) core = core.substring(0, it) }
        core.indexOf('-').let { if (it >= 0) core = core.substring(0, it) }
        return core.split(".").map { part ->
            val digits = part.takeWhile { it.isDigit() }
            digits.toIntOrNull() ?: 0
        }.toIntArray()
    }

    /**
     * Version carried by an unstable asset name: "EVTasker-unstable-1.0.0.42.apk" → "1.0.0.42".
     * The release tag is the fixed string "unstable" (one rolling pre-release), so the asset
     * name is what identifies a build. Null when the name carries no version.
     */
    fun versionFromAssetName(assetName: String): String? =
        Regex("-(\\d[0-9.]*?)\\.apk$", RegexOption.IGNORE_CASE).find(assetName)?.groupValues?.get(1)

    /** True if [remote] is a strictly higher version than [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = segments(remote); val c = segments(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }; val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    /**
     * Asks GitHub for the newest pre-release and returns it if it beats [currentVersion].
     * Runs on the caller's thread — never call from the main thread.
     */
    fun check(currentVersion: String): Update? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "EVTasker-Android")
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Release API returned ${conn.responseCode}"); return null
            }
            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val releases = JSONArray(body)

            // Keep the HIGHEST version, not the first that beats the installed build: the
            // API orders by creation date, and a re-published release would otherwise win.
            // The version comes from the asset name, not the tag: the unstable channel is a
            // single rolling pre-release tagged "unstable", overwritten on every build.
            // Stable releases are skipped — this channel tracks pre-releases only, and a
            // stable APK could not update a .unstable install anyway.
            var best: Update? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (!release.optBoolean("prerelease", false)) continue

                val assets = release.optJSONArray("assets") ?: continue
                for (a in 0 until assets.length()) {
                    val asset = assets.getJSONObject(a)
                    val name = asset.optString("name", "")
                    val lower = name.lowercase(Locale.US)
                    if (!lower.endsWith(".apk") || !lower.contains("unstable")) continue
                    val version = versionFromAssetName(name) ?: continue
                    if (!isNewer(version, currentVersion)) continue
                    if (best != null && !isNewer(version, best.versionName)) continue
                    val url = asset.optString("browser_download_url", "")
                    if (!isAllowedUrl(url)) {
                        Log.w(TAG, "Rejected update URL from an unexpected host: $url"); continue
                    }
                    best = Update(version, url); break
                }
            }
            best
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}"); null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Safe diagnostic name for the downloaded APK. The version comes from a remote asset
     * name, so it is reduced to a safe character set before it reaches a path. Callers looking for
     * an already-downloaded update must use this same name.
     */
    fun downloadFileName(versionName: String?): String {
        val safe = if (versionName.isNullOrEmpty()) "unknown"
        else versionName.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
        return "EVTasker-unstable-$safe.apk"
    }

    /**
     * Downloads into private cache. Every redirect URL is validated before it is followed.
     */
    fun download(context: Context, update: Update): File? {
        if (!isAllowedUrl(update.apkUrl)) {
            Log.w(TAG, "Refusing to download from ${update.apkUrl}"); return null
        }
        val temporary = File.createTempFile(CACHE_PREFIX, ".apk", context.cacheDir)
        val target = File(context.cacheDir, "$CACHE_PREFIX${java.util.UUID.randomUUID()}.apk")
        var current = URL(update.apkUrl)
        try {
            repeat(6) {
                if (!isAllowedUrl(current.toString())) return null
                val connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", "EVTasker-Android")
                }
                try {
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location") ?: return null
                        current = current.toURI().resolve(location).toURL()
                        if (!isAllowedUrl(current.toString())) return null
                    } else {
                        if (status != HttpURLConnection.HTTP_OK) return null
                        FileOutputStream(temporary).use { output ->
                            connection.inputStream.use { input -> input.copyTo(output) }
                            output.fd.sync()
                        }
                        if (!temporary.renameTo(target)) return null
                        return target
                    }
                } finally { connection.disconnect() }
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed", e)
            return null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun install(context: Context, apk: File): Boolean {
        if (!signatureMatchesRunningApp(context, apk)) return false
        return try {
            val process = ProcessBuilder("/system/bin/pm", "install", "-r", apk.absolutePath)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            installSucceeded(exitCode, output)
        } catch (e: Exception) {
            Log.w(TAG, "Update install failed", e)
            false
        }
    }

    fun installSucceeded(exitCode: Int, output: String): Boolean =
        exitCode == 0 && output.contains("Success")

    /**
     * True if [apk] is signed by the same certificate as the running app. Fail closed: an
     * unreadable archive, a missing signature or a failed API call all return false; the
     * caller must delete the file rather than offer it for install.
     */
    fun signatureMatchesRunningApp(context: Context, apk: File): Boolean {
        val archive = ApkSignature.of(context, apk.absolutePath)
        val installed = ApkSignature.ofPackage(context)
        val ok = archive.isNotEmpty() && installed.isNotEmpty() && archive == installed
        if (!ok) Log.w(TAG, "Signature mismatch — refusing update " +
                "(${archive.size} vs ${installed.size} cert(s))")
        return ok
    }
}
