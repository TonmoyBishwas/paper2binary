package com.paper2binary.core

import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.QuestionSpec
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.vision.Homography
import com.paper2binary.core.vision.MarkerDetector
import com.paper2binary.core.vision.Point
import com.paper2binary.core.vision.QrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class MarkerDetectorTest {
    private val spec = SheetSpec(
        "K7M2QX",
        listOf(4, 4, 5, 3, 4, 2).map { QuestionSpec(it) },
        hasComment = true,
    )
    private val form = SyntheticSheet.printedForm(spec)

    @Test
    fun `finds the sheet across many camera angles`() {
        var found = 0
        val total = 30
        for (seed in 0 until total) {
            val random = Random(seed)
            val camera = SyntheticSheet.Camera(
                perspective = 0.08,
                rotationDegrees = random.nextDouble(-15.0, 15.0) + 90.0 * random.nextInt(4),
            )
            val photo = SyntheticSheet.photograph(form, camera, random)
            if (MarkerDetector.locatePage(photo.downscaledTo(1280)) != null) found++
        }
        assertEquals("sheets located", total, found)
    }

    @Test
    fun `QR finder patterns are not mistaken for sheet corners`() {
        val photo = SyntheticSheet.photograph(form, SyntheticSheet.Camera(), Random(7))
        val quad = assertNotNullAnd(MarkerDetector.locatePage(photo))
        // The real sheet spans most of the frame; a quad using QR finders would be far narrower.
        val xs = quad.corners.map { it.x }
        val width = xs.max() - xs.min()
        assert(width > photo.width * 0.45) { "quad too narrow: $width" }
    }

    @Test
    fun `an empty table is not reported as a sheet`() {
        val blank = com.paper2binary.core.imaging.GrayImage(800, 1000)
        blank.pixels.fill(120)
        assertNull(MarkerDetector.locatePage(blank))
    }

    @Test
    fun `homography recovers known points`() {
        val from = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 100.0), Point(0.0, 100.0))
        val to = listOf(Point(10.0, 20.0), Point(210.0, 40.0), Point(190.0, 260.0), Point(5.0, 230.0))
        val h = Homography.fromPoints(from, to)!!
        from.zip(to).forEach { (f, t) ->
            val p = h.apply(f.x, f.y)
            assertEquals(t.x, p.x, 1e-6)
            assertEquals(t.y, p.y, 1e-6)
        }
    }

    @Test
    fun `QR code survives printing and a slanted photo`() {
        val photo = SyntheticSheet.photograph(
            form, SyntheticSheet.Camera(photocopied = true), Random(3),
        )
        val quad = MarkerDetector.locatePage(photo)!!
        // Try the four possible starting corners, as the real pipeline does.
        val mm = SheetLayout.MARKER_CENTERS.map { Point(it.x, it.y) }
        val payloads = (0 until 4).mapNotNull { r ->
            val rotated = List(4) { quad.corners[(it + r) % 4] }
            val h = Homography.fromPoints(mm, rotated)!!
            val qr = SheetLayout.QR_RECT
            val crop = h.warp(photo, 240, 240, 240.0 / qr.width, qr.left, qr.top)
            QrCodec.decode(crop)
        }
        assertEquals(listOf(spec.encode()), payloads)
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
