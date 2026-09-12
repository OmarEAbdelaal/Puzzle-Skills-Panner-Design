package com.puzzleskills.panner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.exifinterface.media.ExifInterface
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Photos the user adds, kept as real files and served to the page over the
 * app's own https origin.
 *
 * The first version of this handed the page base64 data URLs. That was the
 * wrong shape: a downscaled photo is still several hundred kilobytes, which
 * becomes a ~700 KB JavaScript string per photo once base64-encoded and
 * escaped, pushed through evaluateJavascript. Large evaluateJavascript
 * payloads are unreliable, and when they fail they fail silently — the photo
 * simply never appears, with nothing to show the user.
 *
 * Now each photo is written to filesDir and the page is handed a short URL
 * under [PATH]. Same origin as the page, so the export canvas is still not
 * tainted; a few dozen bytes per photo instead of a megabyte; and the files
 * outlive the process, so a restored project still has its images.
 */
object PickedImages {

    private const val TAG = "PickedImages"
    private const val DIR = "picked"

    /** Served path prefix, matching the handler registered in MainActivity. */
    const val PATH = "/picked/"

    /**
     * Chosen from what the banner actually prints: an image placed at 13 cm on
     * its longest side needs about 1535 px at 300 DPI.
     */
    private const val MAX_EDGE = 1800
    private const val JPEG_QUALITY = 88

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /* ── Taking a photo in ───────────────────────────────────── */

    /**
     * Decodes, downscales and stores one picked image.
     *
     * @return the page's image entry {src, w, h, name}, or null with the
     *         reason logged if the image could not be read at all.
     */
    fun save(context: Context, uri: Uri): JSONObject? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "not a decodable image: $uri")
                return null
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888   // keeps PNG transparency
            }
            var bmp = open(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            bmp = applyExifRotation(context, uri, bmp)
            bmp = clampToMaxEdge(bmp)

            // Cut-out artwork on felt depends on transparency, so PNG stays PNG.
            // Re-encoding it as JPEG would replace the transparent background
            // with a solid block.
            val png = keepsAlpha(context, uri, bounds.outMimeType)
            val ext = if (png) "png" else "jpg"
            val id = "p" + System.currentTimeMillis().toString(36) +
                (0..0xFFFF).random().toString(36) + "." + ext
            val file = File(dir(context), id)

            FileOutputStream(file).use { out ->
                if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                else bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }

            val entry = JSONObject()
                .put("src", "https://${MainActivity.APP_HOST}$PATH$id")
                .put("w", bmp.width)
                .put("h", bmp.height)
                .put("name", displayName(context, uri))
            bmp.recycle()
            entry
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory reading $uri", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "could not read $uri", e)
            null
        }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "cannot open $uri", e)
            null
        }

    /**
     * PNG and WebP can carry an alpha channel. The declared MIME type is the
     * best signal, with the decoder's own sniffed type as the fallback for
     * providers that report nothing useful.
     */
    private fun keepsAlpha(context: Context, uri: Uri, sniffed: String?): Boolean {
        val declared = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        val type = (declared ?: sniffed ?: "").lowercase()
        return type.contains("png") || type.contains("webp") ||
            uri.toString().lowercase().endsWith(".png")
    }

    /** Largest power-of-two reduction that still leaves both edges >= MAX_EDGE. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_EDGE && h / 2 >= MAX_EDGE) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    /**
     * Cameras record orientation in EXIF rather than rotating the pixels, so a
     * photo taken in portrait arrives sideways unless this is applied.
     */
    private fun applyExifRotation(context: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = try {
            open(context, uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return try {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            bmp
        }
    }

    /** inSampleSize only halves, so trim the remainder down to MAX_EDGE exactly. */
    private fun clampToMaxEdge(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MAX_EDGE) return bmp
        val ratio = MAX_EDGE.toFloat() / longest
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            if (scaled != bmp) bmp.recycle()
            scaled
        } catch (e: OutOfMemoryError) {
            bmp
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fallback = "صورة"
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.substringBeforeLast('.')?.take(20) ?: fallback
                } else fallback
            } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /* ── Serving them back to the page ───────────────────────── */

    /**
     * Serves [PATH] out of filesDir, on the same origin as the page so the
     * export canvas stays untainted.
     */
    class Handler(private val context: Context) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            // Only ever a bare file name — never a caller-supplied traversal.
            val name = path.substringAfterLast('/')
            if (name.isEmpty() || name.contains("..") || name.contains('/')) return null
            val file = File(dir(context), name)
            if (!file.exists() || !file.isFile) return null
            return try {
                val mime = if (name.endsWith(".png")) "image/png" else "image/jpeg"
                WebResourceResponse(mime, null, FileInputStream(file)).apply {
                    responseHeaders = mapOf("Cache-Control" to "no-store")
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot serve $name", e)
                null
            }
        }
    }

    /* ── Housekeeping ────────────────────────────────────────── */

    /**
     * Deletes stored photos the page no longer refers to. Driven by the page,
     * which is the only thing that knows what its projects and undo history
     * still point at.
     */
    fun retainOnly(context: Context, srcsJson: String): Int {
        return try {
            val keep = HashSet<String>()
            val arr = JSONArray(srcsJson)
            for (i in 0 until arr.length()) {
                keep.add(arr.optString(i).substringAfterLast('/'))
            }
            var removed = 0
            dir(context).listFiles()?.forEach { f ->
                if (!keep.contains(f.name) && f.delete()) removed++
            }
            removed
        } catch (e: Exception) {
            Log.w(TAG, "prune failed", e)
            0
        }
    }
}
