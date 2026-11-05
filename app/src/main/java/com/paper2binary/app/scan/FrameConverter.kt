package com.paper2binary.app.scan

import androidx.camera.core.ImageProxy
import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.vision.Point
import kotlin.math.max

/** Turns live camera frames into small grayscale images for the corner detector. */
object FrameConverter {
    /** Detection has no need for more pixels, and fewer keep the preview smooth. */
    private const val MAX_FRAME_SIDE = 1100

    class Frame(
        val image: GrayImage,
        /** Scale from this image back up to the frame as the camera delivered it. */
        private val rotationDegrees: Int,
    ) {
        val uprightWidth = if (rotationDegrees % 180 == 0) image.width else image.height
        val uprightHeight = if (rotationDegrees % 180 == 0) image.height else image.width

        /** Converts a point in this image to the screen-upright frame. */
        fun toUpright(p: Point): Point = when (rotationDegrees) {
            90 -> Point(image.height - p.y, p.x)
            180 -> Point(image.width - p.x, image.height - p.y)
            270 -> Point(p.y, image.width - p.x)
            else -> p
        }
    }

    /**
     * Uses only the luminance plane of the YUV frame, skipping pixels to reach a suitable size.
     * Preview frames just need the corners located; answers always come from the full photo.
     */
    fun convert(proxy: ImageProxy): Frame {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        var step = 1
        while (max(proxy.width, proxy.height) / step > MAX_FRAME_SIDE) step++
        val w = proxy.width / step
        val h = proxy.height / step

        val out = GrayImage(w, h)
        for (y in 0 until h) {
            val rowStart = y * step * rowStride
            for (x in 0 until w) {
                out.pixels[y * w + x] = buffer.get(rowStart + x * step * pixelStride)
            }
        }
        return Frame(out, proxy.imageInfo.rotationDegrees)
    }
}
