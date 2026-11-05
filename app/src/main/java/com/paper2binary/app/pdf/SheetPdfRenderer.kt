package com.paper2binary.app.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.paper2binary.core.layout.RectMm
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.Survey
import com.paper2binary.core.vision.QrCodec
import java.io.File
import java.io.FileOutputStream

/** How the master sheet is arranged on the printed page. */
enum class PrintLayout {
    /** Two identical half-sheets on one A4 landscape page, to be cut down the dashed line. */
    TWO_ON_A4,

    /** One half-sheet on its own A5 page. */
    SINGLE_A5,
}

/**
 * Draws the printable master sheet as a PDF.
 *
 * Everything is pure black on white, with no greys and no filled areas beyond the small markers
 * and the QR code, so it costs little ink and photocopies cleanly. The PDF always contains the
 * same sheet: print or photocopy it as many times as needed, since all copies are read the same.
 *
 * Android's text engine does the drawing, so any script the phone can display, including Bengali
 * with its conjuncts, comes out correctly shaped and embedded in the PDF.
 */
object SheetPdfRenderer {
    private const val POINTS_PER_MM = 72f / 25.4f

    /** Drawing units per mm. A whole number of units gives text layout enough precision. */
    private const val UNITS_PER_MM = 10f

    fun render(survey: Survey, layoutMode: PrintLayout, output: File) {
        val document = PdfDocument()
        when (layoutMode) {
            PrintLayout.TWO_ON_A4 -> {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(points(297.0), points(210.0), 1).create(),
                )
                val canvas = page.canvas
                drawSheetAt(canvas, survey, 0f)
                drawSheetAt(canvas, survey, 148.5f)
                drawCutLine(canvas)
                document.finishPage(page)
            }

            PrintLayout.SINGLE_A5 -> {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(
                        points(SheetLayout.SHEET_WIDTH),
                        points(SheetLayout.SHEET_HEIGHT),
                        1,
                    ).create(),
                )
                drawSheetAt(page.canvas, survey, 0f)
                document.finishPage(page)
            }
        }
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { document.writeTo(it) }
        document.close()
    }

    private fun points(mm: Double): Int = Math.round(mm * POINTS_PER_MM).toInt()

    private fun drawCutLine(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
        }
        val x = 148.5f * POINTS_PER_MM
        canvas.drawLine(x, 0f, x, canvas.height.toFloat(), paint)
    }

    private fun drawSheetAt(canvas: Canvas, survey: Survey, offsetMm: Float) {
        canvas.save()
        canvas.translate(offsetMm * POINTS_PER_MM, 0f)
        val scale = POINTS_PER_MM / UNITS_PER_MM
        canvas.scale(scale, scale)
        drawSheet(canvas, survey)
        canvas.restore()
    }

    private fun drawSheet(canvas: Canvas, survey: Survey) {
        val layout = SheetLayout.compute(survey.toSpec())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 0.35f * UNITS_PER_MM
        }

        drawMarkers(canvas, ink)
        drawQr(canvas, survey, ink)

        val header = SheetLayout.HEADER_RECT
        val titleHeight = header.height * 0.55
        drawText(
            canvas, survey.title, header.left, header.top, header.right, header.top + titleHeight,
            sizeMm = 4.6f, bold = true,
        )
        drawText(
            canvas, survey.instructions, header.left, header.top + titleHeight, header.right, header.bottom,
            sizeMm = 2.7f,
        )

        layout.questions.forEachIndexed { qi, cell ->
            val question = survey.questions[qi]
            drawText(
                canvas, "${qi + 1}. ${question.text}",
                cell.text.left, cell.text.top, cell.text.right, cell.text.bottom,
                sizeMm = 3.3f, bold = true, anchorBottom = true,
            )
            cell.options.forEachIndexed { oi, option ->
                canvas.drawRect(option.box.toUnits(), line)
                val label = option.label
                drawText(
                    canvas, question.options[oi], label.left, label.top, label.right, label.bottom,
                    sizeMm = 3.0f, centerVertically = true,
                )
            }
        }

        layout.commentLabel?.let { r ->
            drawText(
                canvas, survey.commentPrompt, r.left, r.top, r.right, r.bottom,
                sizeMm = 3.0f, bold = true, anchorBottom = true,
            )
        }
        layout.comment?.let { canvas.drawRect(it.toUnits(), line) }
    }

    private fun drawMarkers(canvas: Canvas, ink: Paint) {
        val size = SheetLayout.MARKER_SIZE
        val ring = SheetLayout.MARKER_RING
        val dot = SheetLayout.MARKER_DOT
        val stroke = Paint(ink).apply {
            style = Paint.Style.STROKE
            strokeWidth = (ring * UNITS_PER_MM).toFloat()
        }
        SheetLayout.MARKER_CENTERS.forEach { c ->
            val middle = (size - ring) / 2
            canvas.drawRect(RectMm(c.x - middle, c.y - middle, c.x + middle, c.y + middle).toUnits(), stroke)
            canvas.drawRect(RectMm(c.x - dot / 2, c.y - dot / 2, c.x + dot / 2, c.y + dot / 2).toUnits(), ink)
        }
    }

    private fun drawQr(canvas: Canvas, survey: Survey, ink: Paint) {
        val matrix = QrCodec.encode(survey.toSpec().encode())
        val rect = SheetLayout.QR_RECT
        val module = rect.width / (matrix.width + 2 * QrCodec.QUIET_MODULES)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix.get(x, y)) continue
                val left = rect.left + (x + QrCodec.QUIET_MODULES) * module
                val top = rect.top + (y + QrCodec.QUIET_MODULES) * module
                canvas.drawRect(RectMm(left, top, left + module, top + module).toUnits(), ink)
            }
        }
    }

    private fun RectMm.toUnits() = RectF(
        (left * UNITS_PER_MM).toFloat(),
        (top * UNITS_PER_MM).toFloat(),
        (right * UNITS_PER_MM).toFloat(),
        (bottom * UNITS_PER_MM).toFloat(),
    )

    /**
     * Fits text in a rectangle by trying smaller sizes before cutting it short, so long wording
     * stays readable and can never spill onto the boxes the scanner measures.
     */
    private fun drawText(
        canvas: Canvas,
        text: String,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        sizeMm: Float,
        bold: Boolean = false,
        centerVertically: Boolean = false,
        anchorBottom: Boolean = false,
    ) {
        if (text.isBlank()) return
        val width = ((right - left) * UNITS_PER_MM).toInt().coerceAtLeast(1)
        val maxHeight = ((bottom - top) * UNITS_PER_MM).toInt()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        var size = sizeMm * UNITS_PER_MM
        val minSize = 2.1f * UNITS_PER_MM
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0.95f)
                .setIncludePad(false)
                .build()
            if (layout.height <= maxHeight || size <= minSize) break
            size -= 0.15f * UNITS_PER_MM
        }
        if (layout.height > maxHeight) {
            // Still too long at the smallest size: cut it off with an ellipsis.
            val lineHeight = layout.height.toFloat() / layout.lineCount
            val lines = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0.95f)
                .setIncludePad(false)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setMaxLines(lines)
                .build()
        }

        val y = when {
            centerVertically -> (top * UNITS_PER_MM) + (maxHeight - layout.height) / 2.0
            anchorBottom -> (bottom * UNITS_PER_MM) - layout.height - 0.4 * UNITS_PER_MM
            else -> top * UNITS_PER_MM
        }
        canvas.save()
        canvas.translate((left * UNITS_PER_MM).toFloat(), y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
}
