// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the text in a picture (a screenshot or photo of a provider's message) on this device, with Tesseract
 * (open source, Apache-2.0). Nothing is sent anywhere. Smart Add then picks the details out of the text.
 */
@Singleton
class ImageText @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Mutex()

    /** The text in the picture at [uri] (a file:// or content:// address). */
    suspend fun read(uri: Uri): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val bitmap = load(uri) ?: throw IllegalArgumentException(com.vantaview.app.ui.tr("That picture couldn't be opened."))
            val tess = TessBaseAPI()
            try {
                if (!tess.init(dataDir().absolutePath, "eng")) throw IllegalStateException(com.vantaview.app.ui.tr("Text reading couldn't start on this device."))
                // Logins and links: keep characters as they are, and treat the picture as a block of text.
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tess.setImage(bitmap)
                tess.utF8Text.orEmpty()
            } finally {
                tess.recycle()
                bitmap.recycle()
            }
        }
    }

    /** Copies the model out of the app the first time, where Tesseract can read it. */
    private fun dataDir(): File {
        val root = File(context.filesDir, "tesseract")
        val model = File(root, "tessdata/eng.traineddata")
        if (!model.exists() || model.length() < 1_000_000) {
            model.parentFile?.mkdirs()
            context.assets.open("tessdata/eng.traineddata").use { i -> model.outputStream().use { i.copyTo(it) } }
        }
        return root
    }

    /** The picture, at most 2400 pixels across, in grey (reads better and uses less memory). */
    private fun load(uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val longest = maxOf(opts.outWidth, opts.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (longest / sample > 2400) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val grey = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        Canvas(grey).drawBitmap(raw, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
        raw.recycle()
        return grey
    }
}
