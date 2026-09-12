package com.puzzleskills.panner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickedImagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(
        context, "${BuildConfig.APPLICATION_ID}.fileprovider", file
    )

    private fun image(name: String, format: Bitmap.CompressFormat): Uri {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.setPixel(10, 10, Color.RED)
        file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
        bitmap.recycle()
        return contentUri(file)
    }

    @Test
    fun importsJpegFromContentUriAndServesStoredPixels() {
        val uri = image("camera.jpg", Bitmap.CompressFormat.JPEG)
        // Android reports dimensions but deliberately returns null in this pass.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use {
            assertNull(BitmapFactory.decodeStream(it, null, bounds))
        }
        assertEquals(48, bounds.outWidth)

        val entry = requireNotNull(PickedImages.save(context, uri))
        assertEquals(48, entry.getInt("w"))
        assertEquals(32, entry.getInt("h"))
        assertEquals("camera", entry.getString("name"))
        val src = entry.getString("src")
        assertTrue(src.startsWith("https://${MainActivity.APP_HOST}${PickedImages.PATH}"))

        // A new handler must still serve the import after the picker is gone.
        val response = requireNotNull(PickedImages.Handler(context).handle(src.substringAfterLast('/')))
        assertEquals("image/jpeg", response.mimeType)
        response.data.use {
            val decoded = requireNotNull(BitmapFactory.decodeStream(it))
            assertEquals(48, decoded.width)
            assertEquals(32, decoded.height)
            decoded.recycle()
        }
    }

    @Test
    fun importsTransparentPngWithoutFlatteningIt() {
        val entry = requireNotNull(PickedImages.save(context, image("cutout.png", Bitmap.CompressFormat.PNG)))
        val name = entry.getString("src").substringAfterLast('/')
        val response = requireNotNull(PickedImages.Handler(context).handle(name))
        assertEquals("image/png", response.mimeType)
        response.data.use {
            val decoded = requireNotNull(BitmapFactory.decodeStream(it))
            assertEquals(0, Color.alpha(decoded.getPixel(0, 0)))
            assertEquals(Color.RED, decoded.getPixel(10, 10))
            decoded.recycle()
        }
    }

    @Test
    fun rejectsCorruptAndMissingImagesWithoutSavingThem() {
        val corrupt = File(context.filesDir, "broken.jpg").apply { writeText("not an image") }
        assertNull(PickedImages.save(context, contentUri(corrupt)))
        assertNull(PickedImages.save(context, contentUri(File(context.filesDir, "missing.png"))))
        assertTrue(File(context.filesDir, "picked").listFiles().isNullOrEmpty())
    }
}
