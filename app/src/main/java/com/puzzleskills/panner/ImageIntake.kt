package com.puzzleskills.panner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Turns a photo the user picked into something the page can lay out.
 *
 * Phone photos are 12 megapixels and up. Handing those to the WebView whole
 * would cost tens of megabytes each as data URLs and stall the layout engine,
 * so every photo is decoded at a reduced sample size, rotated upright
 * according to its EXIF orientation, and re-encoded as a modest JPEG.
 *
 * [MAX_EDGE] is chosen from what the banner actually prints: an image placed
 * at 13 cm on its longest side needs about 1535 px at 300 DPI, so 1800 px
 * leaves headroom for larger placements without wasting memory.
 */
object ImageIntake {

    private const val TAG = "ImageIntake"
    private const val MAX_EDGE = 1800
    private const val JPEG_QUALITY = 88

    /**
     * Decodes, downscales and encodes one image.
     * Returns a JSON object shaped as the page's image entries, or null if the
     * photo could not be read at all.
     */
    fun load(context: Context, uri: Uri): JSONObject? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            bmp = applyExifRotation(context, uri, bmp)
            bmp = clampToMaxEdge(bmp)

            val bytes = ByteArrayOutputStream(256 * 1024).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)

            JSONObject()
                .put("src", "data:image/jpeg;base64,$encoded")
                .put("w", bmp.width)
                .put("h", bmp.height)
                .put("name", displayName(context, uri))
                .also { bmp.recycle() }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "out of memory decoding $uri", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "could not read $uri", e)
            null
        }
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
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
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
}
