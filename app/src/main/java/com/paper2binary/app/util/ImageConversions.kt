package com.paper2binary.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.paper2binary.core.imaging.GrayImage
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Bridges between Android bitmaps and the engine's [GrayImage]. */
object ImageConversions {
    /** Largest photo side worth keeping; beyond this only costs memory and time. */
    private const val MAX_PHOTO_SIDE = 4200

    /** Decodes a photo at sufficient resolution and converts it for the engine. */
    fun decodeForScan(bytes: ByteArray): GrayImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE / 2 &&
            max(bounds.outWidth, bounds.outHeight) / sample > MAX_PHOTO_SIDE
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return bitmap.toGrayDarkestChannel().also { bitmap.recycle() }
    }

    /** See [GrayImage.fromArgbDarkestChannel] for why this keeps the darkest colour channel. */
    private fun Bitmap.toGrayDarkestChannel(): GrayImage {
        val out = GrayImage(width, height)
        // Row by row, so a 12 megapixel photo never needs a 48 MB pixel array.
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            val base = y * width
            for (x in 0 until width) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out.pixels[base + x] = minOf(r, g, b).toByte()
            }
        }
        return out
    }

    fun GrayImage.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val v = pixels[base + x].toInt() and 0xFF
                row[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    fun GrayImage.saveJpeg(file: File, quality: Int) {
        val bitmap = toBitmap()
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bitmap.recycle()
    }
}
