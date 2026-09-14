package com.puzzleskills.panner

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * In-app updates, sourced from this project's GitHub Releases.
 *
 * Flow: ask the Releases API for the newest tag → compare it with the running
 * [BuildConfig.VERSION_NAME] → download the release's APK with DownloadManager
 * → hand it to the system package installer.
 *
 * Because every build is signed with the same key (see the workflow in
 * .github/workflows/), the new APK installs straight over the old one and the
 * user keeps their saved layout.
 */
class UpdateManager(
    private val context: Context,
    private val onResult: (JSONObject) -> Unit
) {

    companion object {
        private const val TAG = "UpdateManager"

        const val REPO = "OmarEAbdelaal/Puzzle-Skills-Panner-Design"
        const val REPO_URL = "https://github.com/$REPO"
        private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

        private const val PREFS = "panner_updates"
        private const val KEY_LAST_CHECK = "last_check"
        private val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000   // once a day

        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val io = Executors.newSingleThreadExecutor()
    private var downloadId = -1L
    private var receiver: BroadcastReceiver? = null

    /* ── Checking ─────────────────────────────────────────── */

    /**
     * @param userInitiated true when the user tapped "check for updates" —
     *        those checks always run and always report back. Automatic checks
     *        are throttled to once a day and stay silent when nothing is new.
     */
    fun check(userInitiated: Boolean) {
        if (!userInitiated && !dueForAutoCheck()) return
        io.execute {
            val result = try {
                fetchLatest(userInitiated)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                JSONObject()
                    .put("error", e.message ?: "network")
                    .put("userInitiated", userInitiated)
            }
            onResult(result)
        }
    }

    private fun dueForAutoCheck(): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        if (now - last < AUTO_CHECK_INTERVAL_MS) return false
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        return true
    }

    private fun fetchLatest(userInitiated: Boolean): JSONObject {
        val conn = (URL(API_LATEST).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "PuzzleSkillsPanner/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // No release published yet — not an error worth alarming anyone about.
                return JSONObject()
                    .put("available", false)
                    .put("userInitiated", userInitiated)
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(body)

            val tag = release.optString("tag_name").ifEmpty { release.optString("name") }
            val latest = normalize(tag)
            val current = normalize(BuildConfig.VERSION_NAME)
            val apkUrl = findApkAsset(release)

            val newer = isNewer(latest, current) && apkUrl != null
            return JSONObject()
                .put("available", newer)
                .put("version", latest)
                .put("current", current)
                .put("url", apkUrl ?: "")
                .put("notes", release.optString("body").take(1200))
                .put("userInitiated", userInitiated)
        } finally {
            conn.disconnect()
        }
    }

    private fun findApkAsset(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                val url = a.optString("browser_download_url")
                if (url.isNotEmpty()) return url
            }
        }
        return null
    }

    /** Strips a leading "v" so "v1.2.0" and "1.2.0" compare equal. */
    private fun normalize(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * Numeric, component-wise comparison: 1.10.0 is newer than 1.9.3.
     * Trailing suffixes ("1.2.0-beta") are ignored for ordering, which is
     * enough for a sideloaded app that only ever moves forward.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.split('.', '-', '+')
            .mapNotNull { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() }

    /* ── Downloading + installing ─────────────────────────── */

    /**
     * Queues the APK download. When it finishes, the system installer opens.
     * Downloads land in the app's own external files directory, so no storage
     * permission is involved and the file is cleaned up with the app.
     */
    fun download(url: String, version: String, onProgress: (Int) -> Unit) {
        if (url.isEmpty()) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val fileName = "PuzzleSkills-Panner-$version.apk"
        File(dir, fileName).takeIf { it.exists() }?.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(context.getString(R.string.update_download_title))
            .setDescription(context.getString(R.string.update_download_desc, version))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, fileName)
            .setAllowedOverRoaming(false)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        registerCompletionReceiver(dm, File(dir, fileName))
        downloadId = dm.enqueue(request)
        pollProgress(dm, onProgress)
    }

    private fun pollProgress(dm: DownloadManager, onProgress: (Int) -> Unit) {
        io.execute {
            var lastPct = -1
            while (downloadId > 0) {
                var cursor: Cursor? = null
                try {
                    cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                    if (cursor == null || !cursor.moveToFirst()) break
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val done = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt()
                        if (pct != lastPct && pct % 10 == 0) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL ||
                        status == DownloadManager.STATUS_FAILED
                    ) break
                } catch (e: Exception) {
                    Log.w(TAG, "progress poll stopped", e)
                    break
                } finally {
                    cursor?.close()
                }
                try { Thread.sleep(600) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun registerCompletionReceiver(dm: DownloadManager, apk: File) {
        unregister()
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id != downloadId) return
                downloadId = -1L
                unregister()
                if (apk.exists() && apk.length() > 0) install(apk) else reportInstallFailure()
            }
        }
        receiver = r
        ContextCompat.registerReceiver(
            context, r,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun unregister() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
    }

    /**
     * Applies the update.
     *
     * Goes through [ApkInstaller], which uses an install session rather than an
     * ACTION_VIEW intent so the app can update itself with as little ceremony as
     * the platform allows, and can come back up afterwards.
     *
     * On Android 8+ the user must first allow this app to install unknown apps;
     * if they haven't, we send them to exactly that settings screen rather than
     * failing silently.
     */
    fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            pendingApk = apk
            openUnknownSourcesSettings()
            return
        }
        ApkInstaller.install(context, apk) { reason ->
            Log.e(TAG, "install failed: $reason")
            reportInstallFailure()
        }
    }

    /** Set aside while the user grants the install-unknown-apps permission. */
    var pendingApk: File? = null
        private set

    /** Called once the user returns from settings, to resume the install. */
    fun resumePendingInstall() {
        val apk = pendingApk ?: return
        pendingApk = null
        if (apk.exists()) install(apk)
    }

    private fun openUnknownSourcesSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "unknown-sources settings unavailable", e)
        }
    }

    private fun reportInstallFailure() {
        onResult(
            JSONObject()
                .put("error", "install")
                .put("userInitiated", true)
        )
    }

    /** Opens the project's GitHub page, where releases can also be downloaded by hand. */
    fun openRepo() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$REPO_URL/releases/latest"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no browser available", e)
        }
    }

    fun dispose() {
        unregister()
        io.shutdownNow()
    }
}
