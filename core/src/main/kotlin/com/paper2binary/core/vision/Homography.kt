package com.paper2binary.core.vision

import com.paper2binary.core.imaging.GrayImage
import kotlin.math.abs

data class Point(val x: Double, val y: Double)

/** A 3x3 projective transform, used to undo the perspective of a hand-held photo. */
class Homography private constructor(private val m: DoubleArray) {

    fun apply(x: Double, y: Double): Point {
        val w = m[6] * x + m[7] * y + m[8]
        return Point((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /**
     * Renders a flat, perspective-corrected image.
     *
     * @param pixelsPerUnit output scale; with this transform mapping mm to photo pixels, a value
     *                      of 10 gives 10 output pixels per millimetre
     * @param originX left edge of the output in source units
     * @param originY top edge of the output in source units
     */
    fun warp(
        source: GrayImage,
        outWidth: Int,
        outHeight: Int,
        pixelsPerUnit: Double,
        originX: Double = 0.0,
        originY: Double = 0.0,
    ): GrayImage {
        val out = GrayImage(outWidth, outHeight)
        val step = 1.0 / pixelsPerUnit
        for (oy in 0 until outHeight) {
            val v = originY + oy * step
            for (ox in 0 until outWidth) {
                val u = originX + ox * step
                val w = m[6] * u + m[7] * v + m[8]
                val sx = (m[0] * u + m[1] * v + m[2]) / w
                val sy = (m[3] * u + m[4] * v + m[5]) / w
                out.pixels[oy * outWidth + ox] = source.sample(sx, sy).toByte()
            }
        }
        return out
    }

    companion object {
        /** The transform that maps each point of [from] onto the matching point of [to]. */
        fun fromPoints(from: List<Point>, to: List<Point>): Homography? {
            require(from.size == 4 && to.size == 4) { "need exactly four point pairs" }
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = from[i]
                val (u, v) = to[i]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val h = solve(a) ?: return null
            return Homography(doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0))
        }

        /** Gaussian elimination with partial pivoting on an 8x9 augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = a.size
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) {
                    if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
                }
                if (abs(a[pivot][col]) < 1e-10) return null
                val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
                for (row in col + 1 until n) {
                    val factor = a[row][col] / a[col][col]
                    for (c in col..n) a[row][c] -= factor * a[col][c]
                }
            }
            val out = DoubleArray(n)
            for (row in n - 1 downTo 0) {
                var sum = a[row][n]
                for (c in row + 1 until n) sum -= a[row][c] * out[c]
                out[row] = sum / a[row][row]
            }
            return out
        }
    }
}
