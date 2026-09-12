package com.puzzleskills.panner

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The whole app: one WebView running the Puzzle Skills banner designer
 * bundled in assets/www, plus a native bridge for the things a web page
 * cannot do on its own — writing to the gallery and updating itself.
 *
 * The page is served through [WebViewAssetLoader] on an https:// origin
 * rather than loaded as file://. That matters: a file:// page gets a unique
 * opaque origin, which would taint the export canvas (breaking toBlob) and
 * make localStorage unreliable. On a real origin both work normally.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val START_URL = "https://$APP_HOST/assets/www/index.html"
        const val JS_INTERFACE = "PannerNative"
        const val MAX_PICK = 30

        /**
         * Named explicitly rather than relying on "image/*": some providers
         * filter on the concrete type, and PNG and JPEG are what matters here.
         */
        val IMAGE_MIME_TYPES = arrayOf(
            "image/png", "image/jpeg", "image/jpg", "image/webp",
            "image/gif", "image/bmp", "image/heic", "image/heif", "image/*"
        )
    }

    private lateinit var webView: WebView
    private lateinit var exports: ExportStore
    private lateinit var updates: UpdateManager

    /** Set while the page has a <input type="file"> picker open. */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        cb?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    /** Off the main thread: decoding and re-encoding photos is slow. */
    private val io = Executors.newSingleThreadExecutor()

    /**
     * The system document picker, opened with PNG and JPEG named explicitly.
     *
     * This replaced Android's visual photo picker, which only lists media the
     * MediaStore has indexed — so images sitting in Downloads, a WhatsApp
     * folder, or anything copied over from a computer simply were not offered,
     * which is what "it won't accept my image" looked like. The document
     * picker browses everything, gallery apps included.
     */
    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> ingestPickedPhotos(urisFrom(result.resultCode, result.data)) }

    /** Android's visual photo picker, offered as the gallery-shaped alternative. */
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> ingestPickedPhotos(urisFrom(result.resultCode, result.data)) }

    /* ── Lifecycle ────────────────────────────────────────── */

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Panner)
        setContentView(R.layout.activity_main)

        exports = ExportStore(applicationContext)
        updates = UpdateManager(applicationContext) { result -> deliverUpdateResult(result) }

        webView = findViewById(R.id.webView)
        applyInsets()
        configureWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(START_URL)
        }

        installBackHandler()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        // The user may have just granted "install unknown apps" for us.
        updates.resumePendingInstall()
    }

    override fun onDestroy() {
        io.shutdownNow()
        updates.dispose()
        exports.abort()
        webView.destroy()
        super.onDestroy()
    }

    /** Keeps the page clear of the status bar and the gesture navigation bar. */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler(PickedImages.PATH, PickedImages.Handler(applicationContext))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true              // the manual layout is kept in localStorage
            loadWithOverviewMode = true
            useWideViewPort = true
            // The page implements its own pan/zoom (gestures.js) so that one
            // finger can move an image while two fingers move the canvas.
            // WebView's own pinch zoom would fight it for the same touches.
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Everything the page needs ships inside the APK, so file:// access
            // stays off. content:// stays on: it is how images the user picks
            // from the gallery reach the page.
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setBackgroundColor(0xFFFAEFD7.toInt())

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                // Bundled pages stay in the WebView; anything else is a real
                // link and belongs in the user's browser.
                if (url.host == APP_HOST) return false
                openExternally(url)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                val field = EditText(this@MainActivity).apply {
                    setText(defaultValue.orEmpty())
                    setSelection(text.length)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(field)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(field.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            /** Browser fallback for "add images"; the app uses pickImages(). */
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    filePicker.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    toast(getString(R.string.no_gallery_app))
                    false
                }
            }
        }

        webView.addJavascriptInterface(Bridge(), JS_INTERFACE)
    }

    /**
     * Back closes the app's own overlays first (menu sheet, settings drawer)
     * and only leaves the app once there is nothing left to close.
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(window.PannerHost && window.PannerHost.onBackPressed) " +
                        "? window.PannerHost.onBackPressed() : false"
                ) { value ->
                    if (value != "true") confirmExit()
                }
            }
        })
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_confirm) { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /* ── Bridge callbacks into the page ───────────────────── */

    private fun deliverUpdateResult(result: JSONObject) {
        callJs("onUpdateResult", result.toString())
    }

    /** Invokes window.PannerHost.<fn>("<arg>") on the main thread, if the page defines it. */
    private fun callJs(fn: String, arg: String) {
        val quoted = JSONObject.quote(arg)
        webView.post {
            webView.evaluateJavascript(
                "if (window.PannerHost && window.PannerHost.$fn) window.PannerHost.$fn($quoted);",
                null
            )
        }
    }

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_browser_app))
        }
    }

    /** Stops the screen sleeping mid-export, which would stall the save. */
    private fun keepAwake(on: Boolean) = runOnUiThread {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /* ── Photo intake ─────────────────────────────────────── */

    /** A chooser result is either one URI on the data, or a ClipData of many. */
    private fun urisFrom(resultCode: Int, data: Intent?): List<Uri> {
        if (resultCode != RESULT_OK || data == null) return emptyList()
        val clip = data.clipData
        if (clip != null) {
            return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        }
        return listOfNotNull(data.data)
    }

    /**
     * Opens a picker. Each option is tried in turn so that an unusual device,
     * or a ROM missing one of these activities, still ends up with something.
     */
    private fun launchPicker(preferGallery: Boolean) {
        val attempts = if (preferGallery) {
            listOf(::galleryIntent, ::openDocumentIntent, ::getContentIntent)
        } else {
            listOf(::openDocumentIntent, ::getContentIntent, ::galleryIntent)
        }
        for (build in attempts) {
            try {
                val intent = build()
                if (preferGallery) photoPicker.launch(intent) else documentPicker.launch(intent)
                return
            } catch (e: Exception) {
                Log.w("Panner", "picker attempt failed", e)
            }
        }
        callJs("onPickFailed", getString(R.string.no_gallery_app))
    }

    private fun openDocumentIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_MIME_TYPES, IMAGE_MIME_TYPES)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun getContentIntent() = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_MIME_TYPES, IMAGE_MIME_TYPES)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    private fun galleryIntent() = Intent(Intent.ACTION_PICK).apply {
        setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        putExtra(Intent.EXTRA_MIME_TYPES, IMAGE_MIME_TYPES)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    /**
     * Stores each picked photo and tells the page where to find it, one at a
     * time so a large selection never builds one enormous payload.
     *
     * The page is told how many failed as well as how many worked — a photo
     * that cannot be decoded should say so, not vanish.
     */
    private fun ingestPickedPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) {
            callJs("onPickFailed", getString(R.string.no_images_picked))
            return
        }
        io.execute {
            var ok = 0
            var failed = 0
            for (uri in uris.take(MAX_PICK)) {
                val entry = PickedImages.save(applicationContext, uri)
                if (entry != null) {
                    callJs("onImagePicked", entry.toString())
                    ok++
                } else {
                    failed++
                }
            }
            callJs("onPickDone", JSONObject().put("ok", ok).put("failed", failed).toString())
        }
    }

    /* ── The JavaScript interface ─────────────────────────── */

    /**
     * Every method here runs on the WebView's JavaBridge thread, not the main
     * thread, so anything touching the UI hops back explicitly.
     */
    inner class Bridge {

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun versionCode(): Int = BuildConfig.VERSION_CODE

        /* Export → gallery. The page streams base64 chunks through these. */

        @JavascriptInterface
        fun beginSave(fileName: String): Boolean {
            keepAwake(true)
            val ok = exports.begin(fileName)
            if (!ok) keepAwake(false)
            return ok
        }

        @JavascriptInterface
        fun appendSave(base64Chunk: String): Boolean = exports.append(base64Chunk)

        @JavascriptInterface
        fun endSave(): String {
            val path = exports.end()
            keepAwake(false)
            return path ?: ""
        }

        @JavascriptInterface
        fun abortSave() {
            exports.abort()
            keepAwake(false)
        }

        /** Opens the file browser, which can reach PNG and JPEG anywhere. */
        @JavascriptInterface
        fun pickImages() = runOnUiThread { launchPicker(preferGallery = false) }

        /** Opens the gallery instead, for photos rather than files. */
        @JavascriptInterface
        fun pickImagesFromGallery() = runOnUiThread { launchPicker(preferGallery = true) }

        /** Drops stored photos the page no longer refers to. */
        @JavascriptInterface
        fun retainImages(srcsJson: String) {
            io.execute { PickedImages.retainOnly(applicationContext, srcsJson) }
        }

        /* Updates */

        @JavascriptInterface
        fun checkForUpdates(userInitiated: Boolean) = updates.check(userInitiated)

        @JavascriptInterface
        fun downloadUpdate(url: String, version: String) {
            updates.download(url, version) { pct -> callJs("onUpdateProgress", pct.toString()) }
        }

        @JavascriptInterface
        fun openRepo() = updates.openRepo()

        /* Exports folder / sharing */

        @JavascriptInterface
        fun openExportsFolder() {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                toast(getString(R.string.exports_location, exports.albumPath))
            }
        }

        @JavascriptInterface
        fun shareLastExport() {
            val uri = exports.lastSavedUri ?: exports.lastSavedPath?.let { path ->
                // Android 9 and older: wrap the plain file so the receiving app
                // gets a grantable URI rather than an unreadable file:// path.
                runCatching {
                    FileProvider.getUriForFile(
                        this@MainActivity,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        File(path)
                    )
                }.getOrNull()
            }
            if (uri == null) {
                toast(getString(R.string.nothing_exported_yet))
                return
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread {
                startActivity(Intent.createChooser(share, getString(R.string.share_export)))
            }
        }

        @JavascriptInterface
        fun toastMessage(message: String) = toast(message)
    }
}
