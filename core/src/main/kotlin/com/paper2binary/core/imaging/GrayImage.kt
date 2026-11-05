package com.paper2binary.core.imaging

import kotlin.math.max

/**
 * An 8-bit grayscale image stored row by row. The engine works only on this type so the same
 * code runs on camera frames, decoded photos and synthetic test images.
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray = ByteArray(width * height)) {
    init {
        require(pixels.size >= width * height) { "pixel buffer too small" }
    }

    /** Unsigned value (0 = black, 255 = white) at x, y. */
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value.toByte()
    }

    /** Value at a fractional position, using bilinear interpolation. White outside the image. */
    fun sample(x: Double, y: Double): Int {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return 255
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = if (x0 + 1 < width) x0 + 1 else x0
        val y1 = if (y0 + 1 < height) y0 + 1 else y0
        val fx = x - x0
        val fy = y - y0
        val top = this[x0, y0] * (1 - fx) + this[x1, y0] * fx
        val bottom = this[x0, y1] * (1 - fx) + this[x1, y1] * fx
        return (top * (1 - fy) + bottom * fy + 0.5).toInt()
    }

    /** Box-filtered copy whose longer side is at most [maxSide]; returns this if already small. */
    fun downscaledTo(maxSide: Int): GrayImage {
        val factor = max(width, height).toDouble() / maxSide
        if (factor <= 1.0) return this
        val w = (width / factor).toInt().coerceAtLeast(1)
        val h = (height / factor).toInt().coerceAtLeast(1)
        val out = GrayImage(w, h)
        for (y in 0 until h) {
            val sy0 = (y * factor).toInt()
            val sy1 = (((y + 1) * factor).toInt()).coerceIn(sy0 + 1, height)
            for (x in 0 until w) {
                val sx0 = (x * factor).toInt()
                val sx1 = (((x + 1) * factor).toInt()).coerceIn(sx0 + 1, width)
                var sum = 0
                for (sy in sy0 until sy1) {
                    val row = sy * width
                    for (sx in sx0 until sx1) sum += pixels[row + sx].toInt() and 0xFF
                }
                out.pixels[y * w + x] = (sum / ((sy1 - sy0) * (sx1 - sx0))).toByte()
            }
        }
        return out
    }

    /** Copy of a rectangular region, clamped to the image. */
    fun crop(left: Int, top: Int, right: Int, bottom: Int): GrayImage {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(l, width)
        val b = bottom.coerceIn(t, height)
        val out = GrayImage(r - l, b - t)
        for (y in t until b) {
            System.arraycopy(pixels, y * width + l, out.pixels, (y - t) * out.width, r - l)
        }
        return out
    }

    companion object {
        /**
         * Grayscale from packed ARGB pixels, keeping the darkest colour channel of each pixel.
         * White paper stays white in all channels, while ink of any colour is dark in at least
         * one channel, so red and blue pens read almost as strongly as black.
         */
        fun fromArgbDarkestChannel(width: Int, height: Int, argb: IntArray): GrayImage {
            val out = GrayImage(width, height)
            for (i in 0 until width * height) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out.pixels[i] = minOf(r, g, b).toByte()
            }
            return out
        }
    }
}
