package com.paper2binary.core

import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.layout.RectMm
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.vision.Homography
import com.paper2binary.core.vision.Point
import com.paper2binary.core.vision.QrCodec
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws realistic test sheets with Java2D. The positions come from the same [SheetLayout] the
 * app prints from, but the pen strokes, perspective, lighting and noise are all simulated, so
 * the whole pipeline can be measured against known answers without a phone or a printer.
 */
object SyntheticSheet {
    private const val PX_PER_MM = 12.0

    /** How a respondent's pen behaves on one simulated sheet. */
    data class Pen(val widthMm: Double = 0.45, val darkness: Int = 25)

    /** Renders the blank printed form. Returns a sheet image at [PX_PER_MM]. */
    fun printedForm(spec: SheetSpec): BufferedImage {
        val layout = SheetLayout.compute(spec)
        val w = (SheetLayout.SHEET_WIDTH * PX_PER_MM).toInt()
        val h = (SheetLayout.SHEET_HEIGHT * PX_PER_MM).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color.BLACK

        // Corner markers: thick ring with a centred dot.
        SheetLayout.MARKER_CENTERS.forEach { c ->
            val size = SheetLayout.MARKER_SIZE
            val ring = SheetLayout.MARKER_RING
            val outer = Rectangle2D.Double(
                (c.x - size / 2) * PX_PER_MM, (c.y - size / 2) * PX_PER_MM,
                size * PX_PER_MM, size * PX_PER_MM,
            )
            g.fill(outer)
            g.color = Color.WHITE
            val innerSize = size - 2 * ring
            g.fill(
                Rectangle2D.Double(
                    (c.x - innerSize / 2) * PX_PER_MM, (c.y - innerSize / 2) * PX_PER_MM,
                    innerSize * PX_PER_MM, innerSize * PX_PER_MM,
                ),
            )
            g.color = Color.BLACK
            val dot = SheetLayout.MARKER_DOT
            g.fill(
                Rectangle2D.Double(
                    (c.x - dot / 2) * PX_PER_MM, (c.y - dot / 2) * PX_PER_MM,
                    dot * PX_PER_MM, dot * PX_PER_MM,
                ),
            )
        }

        // QR code inside its quiet zone.
        val matrix = QrCodec.encode(spec.encode())
        val qr = SheetLayout.QR_RECT
        val module = qr.width / (matrix.width + 2 * QrCodec.QUIET_MODULES)
        for (my in 0 until matrix.height) for (mx in 0 until matrix.width) {
            if (matrix.get(mx, my)) {
                g.fill(
                    Rectangle2D.Double(
                        (qr.left + (mx + QrCodec.QUIET_MODULES) * module) * PX_PER_MM,
                        (qr.top + (my + QrCodec.QUIET_MODULES) * module) * PX_PER_MM,
                        module * PX_PER_MM + 0.5,
                        module * PX_PER_MM + 0.5,
                    ),
                )
            }
        }

        // Text and boxes. The wording is noise as far as the scanner is concerned.
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (4.0 * PX_PER_MM).toInt())
        g.drawString("Sample survey", (15 * PX_PER_MM).toInt(), (11 * PX_PER_MM).toInt())
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, (2.9 * PX_PER_MM).toInt())
        g.stroke = BasicStroke((0.35 * PX_PER_MM).toFloat())
        layout.questions.forEachIndexed { qi, q ->
            g.drawString(
                "${qi + 1}. How would you rate the item in this question?",
                (q.text.left * PX_PER_MM).toInt(),
                ((q.text.top + 4) * PX_PER_MM).toInt(),
            )
            q.options.forEachIndexed { oi, o ->
                g.draw(o.box.toAwt())
                g.drawString(
                    "Option ${oi + 1}",
                    (o.label.left * PX_PER_MM).toInt(),
                    (o.label.centerY * PX_PER_MM).toInt(),
                )
            }
        }
        layout.comment?.let { g.draw(it.toAwt()) }
        layout.commentLabel?.let {
            g.drawString("Comments", (it.left * PX_PER_MM).toInt(), ((it.bottom - 1) * PX_PER_MM).toInt())
        }
        g.dispose()
        return img
    }

    private fun RectMm.toAwt() = Rectangle2D.Double(
        left * PX_PER_MM, top * PX_PER_MM, width * PX_PER_MM, height * PX_PER_MM,
    )

    /** Draws a hand-made tick in [box], with size, angle and position varied by [random]. */
    fun drawTick(img: BufferedImage, box: RectMm, pen: Pen, random: Random) {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(pen.darkness, pen.darkness, pen.darkness)
        g.stroke = BasicStroke(
            (pen.widthMm * PX_PER_MM).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
        )
        val scale = random.nextDouble(0.75, 1.25)
        val cx = box.centerX + random.nextDouble(-0.6, 0.6)
        val cy = box.centerY + random.nextDouble(-0.6, 0.6)
        val tilt = random.nextDouble(-0.3, 0.3)
        fun pt(dx: Double, dy: Double): Pair<Double, Double> {
            val x = (dx * cos(tilt) - dy * sin(tilt)) * scale + cx
            val y = (dx * sin(tilt) + dy * cos(tilt)) * scale + cy
            return x * PX_PER_MM to y * PX_PER_MM
        }
        val path = Path2D.Double()
        val (x0, y0) = pt(-2.3 + random.nextDouble(-0.3, 0.3), -0.2 + random.nextDouble(-0.3, 0.3))
        val (x1, y1) = pt(-0.6 + random.nextDouble(-0.3, 0.3), 1.8 + random.nextDouble(-0.3, 0.3))
        val (x2, y2) = pt(3.0 + random.nextDouble(-0.5, 0.5), -3.2 + random.nextDouble(-0.5, 0.5))
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
        path.quadTo((x1 + x2) / 2 + random.nextDouble(-6.0, 6.0), (y1 + y2) / 2, x2, y2)
        g.draw(path)
        g.dispose()
    }

    /** Scribbles several words of pretend handwriting into the comment box. */
    fun drawComment(img: BufferedImage, box: RectMm, pen: Pen, random: Random) {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(pen.darkness, pen.darkness, pen.darkness)
        g.stroke = BasicStroke((pen.widthMm * PX_PER_MM).toFloat())
        var x = box.left + 4
        val y = box.top + box.height * 0.4 + random.nextDouble(-2.0, 2.0)
        repeat(random.nextInt(14, 30)) {
            val path = Path2D.Double()
            path.moveTo(x * PX_PER_MM, y * PX_PER_MM)
            repeat(4) {
                x += random.nextDouble(0.5, 1.5)
                path.lineTo(x * PX_PER_MM, (y + random.nextDouble(-2.5, 2.5)) * PX_PER_MM)
            }
            g.draw(path)
            x += random.nextDouble(0.5, 2.0)
        }
        g.dispose()
    }

    /** Camera conditions for [photograph]. */
    data class Camera(
        val photoWidth: Int = 1500,
        val photoHeight: Int = 2000,
        /** How far the corners stray from a perfect top-down view, as a fraction of the size. */
        val perspective: Double = 0.05,
        /** Whole-sheet rotation; a multiple of 90 degrees simulates turning the sheet around. */
        val rotationDegrees: Double = 0.0,
        val fillFraction: Double = 0.85,
        val noiseSigma: Double = 4.0,
        /** Darkest level of the lighting gradient across the photo, 1.0 = perfectly even. */
        val shadow: Double = 0.75,
        val blurRadius: Int = 1,
        /** Reduced contrast and grey background, as produced by a worn photocopier. */
        val photocopied: Boolean = false,
    )

    /** Simulates photographing [sheet] lying on a darker table. */
    fun photograph(sheet: BufferedImage, camera: Camera, random: Random): GrayImage {
        val w = camera.photoWidth
        val h = camera.photoHeight
        val cx = w / 2.0
        val cy = h / 2.0
        val angle = Math.toRadians(camera.rotationDegrees)

        // Scale the sheet to the requested fraction of the frame, whatever its rotation.
        val sinA = kotlin.math.abs(sin(angle))
        val cosA = kotlin.math.abs(cos(angle))
        val boundW = sheet.width * cosA + sheet.height * sinA
        val boundH = sheet.width * sinA + sheet.height * cosA
        val scale = minOf(w * camera.fillFraction / boundW, h * camera.fillFraction / boundH)

        val sw = sheet.width.toDouble()
        val sh = sheet.height.toDouble()
        val sheetCorners = listOf(Point(0.0, 0.0), Point(sw, 0.0), Point(sw, sh), Point(0.0, sh))
        val jitter = camera.perspective * minOf(w, h)
        val photoCorners = sheetCorners.map { p ->
            val dx = (p.x - sw / 2) * scale
            val dy = (p.y - sh / 2) * scale
            Point(
                cx + dx * cos(angle) - dy * sin(angle) + random.nextDouble(-jitter, jitter),
                cy + dx * sin(angle) + dy * cos(angle) + random.nextDouble(-jitter, jitter),
            )
        }
        val photoToSheet = Homography.fromPoints(photoCorners, sheetCorners)!!

        // Copy the sheet into a GrayImage for bilinear sampling.
        val raster = sheet.raster
        val src = GrayImage(sheet.width, sheet.height)
        val row = IntArray(sheet.width)
        for (y in 0 until sheet.height) {
            raster.getSamples(0, y, sheet.width, 1, 0, row)
            for (x in 0 until sheet.width) {
                var v = row[x]
                if (camera.photocopied) v = (70 + v * 0.68).toInt()
                src[x, y] = v
            }
        }

        val gx = random.nextDouble(-1.0, 1.0)
        val gy = random.nextDouble(-1.0, 1.0)
        val table = 70 + random.nextInt(50)
        val out = GrayImage(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = photoToSheet.apply(x.toDouble(), y.toDouble())
                val inside = p.x >= 0 && p.y >= 0 && p.x < sw - 1 && p.y < sh - 1
                val base = if (inside) src.sample(p.x, p.y).toDouble() else table.toDouble()
                val t = ((x.toDouble() / w - 0.5) * gx + (y.toDouble() / h - 0.5) * gy + 1) / 2
                val light = camera.shadow + (1 - camera.shadow) * t
                out[x, y] = (base * light * 0.95).toInt().coerceIn(0, 255)
            }
        }

        val blurred = if (camera.blurRadius > 0) boxBlur(out, camera.blurRadius) else out
        if (camera.noiseSigma > 0) {
            for (i in 0 until w * h) {
                val v = (blurred.pixels[i].toInt() and 0xFF) + gaussian(random) * camera.noiseSigma
                blurred.pixels[i] = v.toInt().coerceIn(0, 255).toByte()
            }
        }
        return blurred
    }

    private fun gaussian(random: Random): Double =
        kotlin.math.sqrt(-2 * kotlin.math.ln(1 - random.nextDouble())) *
            cos(2 * Math.PI * random.nextDouble())

    private fun boxBlur(image: GrayImage, radius: Int): GrayImage {
        val w = image.width
        val h = image.height
        val tmp = IntArray(w * h)
        val size = 2 * radius + 1
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (k in -radius..radius) sum += image[(x + k).coerceIn(0, w - 1), y]
            tmp[y * w + x] = sum / size
        }
        val out = GrayImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (k in -radius..radius) sum += tmp[(y + k).coerceIn(0, h - 1) * w + x]
            out[x, y] = sum / size
        }
        return out
    }

}
