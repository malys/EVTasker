package com.mg4.tasker.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import java.io.File
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
 * Security posture is the one MG4Control settled on in its OTA work:
 *  - the APK URL comes from a remote JSON document and is never trusted: https only,
 *    exact-match host allowlist, re-checked before it reaches the system downloader;
 *  - the downloaded APK must be signed by the same certificate as the running app, or it
 *    is deleted rather than offered for install;
 *  - both checks fail closed.
 */
object OtaUpdater {

    private const val TAG = "OtaUpdater"
    private const val RELEASES_API = "https://api.github.com/repos/malys/MG4Tasker/releases"
    private const val TIMEOUT_MS = 10_000

    // Hosts an update may come from. The githubusercontent entries are the CDNs GitHub
    // redirects release-asset downloads to; without them the download fails.
    private val ALLOWED_HOSTS = setOf(
        "api.github.com", "github.com",
        "objects.githubusercontent.com", "release-assets.githubusercontent.com"
    )

    data class Update(val versionName: String, val apkUrl: String)

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
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "MG4Tasker-Android")
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Release API returned ${conn.responseCode}"); return null
            }
            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val releases = JSONArray(body)

            // Keep the HIGHEST version, not the first that beats the installed build: the
            // API orders by creation date, and a re-published release would otherwise win.
            // Stable releases are skipped — this channel tracks pre-releases only, and a
            // stable APK could not update a .unstable install anyway.
            var best: Update? = null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (!release.optBoolean("prerelease", false)) continue
                val tag = release.optString("tag_name", "")
                if (!isNewer(tag, currentVersion)) continue
                if (best != null && !isNewer(tag, best.versionName)) continue

                val assets = release.optJSONArray("assets") ?: continue
                for (a in 0 until assets.length()) {
                    val asset = assets.getJSONObject(a)
                    val name = asset.optString("name", "").lowercase(Locale.US)
                    if (!name.endsWith(".apk") || !name.contains("unstable")) continue
                    val url = asset.optString("browser_download_url", "")
                    if (!isAllowedUrl(url)) {
                        Log.w(TAG, "Rejected update URL from an unexpected host: $url"); continue
                    }
                    best = Update(tag, url); break
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
     * Name the downloaded APK gets in public Downloads. The version comes from a remote tag,
     * so it is reduced to a safe character set before it reaches a path. Callers looking for
     * an already-downloaded update must use this same name.
     */
    fun downloadFileName(versionName: String?): String {
        val safe = if (versionName.isNullOrEmpty()) "unknown"
        else versionName.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_")
        return "MG4Tasker-unstable-$safe.apk"
    }

    /**
     * Queues the download. The URL is re-checked here: a remote URL is never handed to a
     * system component on the strength of an earlier check alone.
     */
    fun download(context: Context, update: Update) {
        if (!isAllowedUrl(update.apkUrl)) {
            Log.w(TAG, "Refusing to download from ${update.apkUrl}"); return
        }
        val fileName = downloadFileName(update.versionName)
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("MG4Tasker ${update.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Log.i(TAG, "Update queued: $fileName")
    }

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
