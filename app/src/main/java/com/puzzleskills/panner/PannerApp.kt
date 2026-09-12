package com.puzzleskills.panner

import android.app.Application
import android.os.Build
import android.webkit.WebView

/**
 * Application entry point.
 *
 * Nothing heavy happens here — the app is a single WebView screen — but the
 * remote-debugging switch is worth wiring up so a debug build can be inspected
 * from Chrome DevTools without shipping that capability in release builds.
 */
class PannerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}
