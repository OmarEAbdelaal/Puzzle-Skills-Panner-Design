package com.puzzleskills.panner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Installs an update over the running app.
 *
 * This uses [PackageInstaller] rather than firing an ACTION_VIEW intent at the
 * APK. The intent route always drops the user into the system installer screen
 * and leaves them to tap through it and then press "Open"; a session can do
 * better:
 *
 *  • On Android 12+ the platform allows an app to update *itself* with no
 *    prompt at all, provided it is the installer of record for the package.
 *    The first update still asks (the browser that sideloaded the app owns it
 *    at that point); once we have installed one update ourselves, we own it,
 *    and later updates apply silently.
 *  • On Android 14+ [PackageInstaller.SessionParams.setRequestUpdateOwnership]
 *    claims that ownership explicitly.
 *  • Either way we learn the outcome, so the app can be brought back up rather
 *    than simply vanishing.
 *
 * None of this works unless every build is signed with the same key. Android
 * rejects an update whose signature differs from the installed app, whatever
 * install method is used — see docs/SIGNING.md.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    const val ACTION_INSTALL_STATUS = "com.puzzleskills.panner.INSTALL_STATUS"
    const val EXTRA_RELAUNCH = "relaunch"

    /**
     * Streams [apk] into an install session and commits it.
     *
     * @param onFailure called with a human-readable reason if the session could
     *        not even be created or written; the outcome of the install itself
     *        arrives at [InstallResultReceiver], which may run in a fresh
     *        process after this one is killed to apply the update.
     */
    fun install(context: Context, apk: File, onFailure: (String) -> Unit) {
        if (!apk.exists() || apk.length() == 0L) {
            onFailure(context.getString(R.string.update_file_missing))
            return
        }

        var session: PackageInstaller.Session? = null
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // "This app is updating itself" — no prompt once we own it.
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestUpdateOwnership(true)
                }
            }

            val sessionId = installer.createSession(params)
            // Held in a non-null local as well: `session` is reassigned below,
            // and Kotlin will not smart-cast a var that a closure captures.
            val open = installer.openSession(sessionId)
            session = open

            open.openWrite("panner", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out, 256 * 1024) }
                open.fsync(out)
            }

            val intent = Intent(ACTION_INSTALL_STATUS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_RELAUNCH, true)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE   // the installer fills in the result
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)

            open.commit(pending.intentSender)
            open.close()
            session = null
        } catch (e: Exception) {
            Log.e(TAG, "install session failed", e)
            try { session?.abandon() } catch (_: Exception) { }
            onFailure(context.getString(R.string.update_install_failed))
        }
    }

    /** Brings the app back up after an update has been applied. */
    fun relaunch(context: Context) {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            // Android restricts starting an activity from the background, so
            // this can be refused. The update is installed either way; the user
            // just taps the icon.
            Log.w(TAG, "could not relaunch automatically", e)
        }
    }
}

/**
 * Receives the outcome of an install session.
 *
 * Declared in the manifest rather than registered at runtime because applying a
 * self-update kills the process — this has to be deliverable to a brand new one.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_STATUS) return

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The platform wants the user to confirm — usually only until
                // this app becomes the installer of record for itself.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) {
                    try { context.startActivity(confirm) } catch (e: Exception) {
                        Log.w("ApkInstaller", "cannot show install prompt", e)
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                if (intent.getBooleanExtra(ApkInstaller.EXTRA_RELAUNCH, false)) {
                    ApkInstaller.relaunch(context)
                }
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("ApkInstaller", "install did not complete: $msg")
            }
        }
    }
}
