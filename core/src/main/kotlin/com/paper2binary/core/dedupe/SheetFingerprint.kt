package com.paper2binary.core.dedupe

import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.layout.RectMm
import com.paper2binary.core.pipeline.SheetScan
import kotlin.math.sqrt

/**
 * Recognises a sheet that has already been scanned, using the only thing that makes it unique.
 *
 * All copies of the form are identical by design, so there is no serial number to check. What
 * differs is the handwriting: nobody draws the same tick twice. The fingerprint keeps a small
 * picture of each ticked box and of the comment. Rescanning a sheet gives nearly the same
 * pictures, while another person who chose the same answers gives clearly different ones.
 */
object SheetFingerprint {
    private const val BOX_GRID = 12
    private const val COMMENT_COLUMNS = 64
    private const val COMMENT_ROWS = 16
    private const val SHIFT_CELLS = 2

    /** Above this similarity a sheet is treated as a suspected rescan and shown to a person. */
    const val DUPLICATE_THRESHOLD = 0.92

    /** Compact form of the answers, so only sheets with identical answers are ever compared. */
    fun answerKey(answers: List<List<Int>>): String =
        answers.joinToString("|") { a -> if (a.isEmpty()) "-" else a.sorted().joinToString("+") }

    fun compute(scan: SheetScan): ByteArray {
        val px = SheetScan.PX_PER_MM
        val patches = ArrayList<Patch>()
        scan.layout.questions.forEachIndexed { q, question ->
            scan.readings[q].selected.sorted().forEach { option ->
                val inner = question.options[option].box.inset(0.6)
                patches += Patch(BOX_GRID, BOX_GRID, grid(scan.sheet, inner, px, BOX_GRID, BOX_GRID), false)
            }
        }
        val comment = scan.layout.comment
        if (comment != null && scan.commentImage != null) {
            patches += Patch(
                COMMENT_COLUMNS, COMMENT_ROWS,
                grid(scan.sheet, comment.inset(1.5), px, COMMENT_COLUMNS, COMMENT_ROWS),
                true,
            )
        }
        return serialize(patches)
    }

    /**
     * Similarity between two fingerprints, from 0 to 1. Sheets with no writing at all score 0
     * because they contain nothing that could identify them.
     */
    fun similarity(a: ByteArray, b: ByteArray): Double {
        val pa = deserialize(a)
        val pb = deserialize(b)
        if (pa.isEmpty() || pa.size != pb.size) return 0.0

        val boxes = ArrayList<Double>()
        var comment: Double? = null
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            if (x.isComment != y.isComment || x.columns != y.columns || x.rows != y.rows) return 0.0
            val score = bestCorrelation(x, y)
            if (x.isComment) comment = score else boxes += score
        }
        val boxScore = if (boxes.isEmpty()) null else boxes.average()
        return when {
            boxScore != null && comment != null -> (boxScore + comment) / 2
            boxScore != null -> boxScore
            else -> comment ?: 0.0
        }.coerceIn(0.0, 1.0)
    }

    private class Patch(val columns: Int, val rows: Int, val cells: ByteArray, val isComment: Boolean)

    /** Average darkness per grid cell. A coarse grid tolerates small alignment differences. */
    private fun grid(sheet: GrayImage, area: RectMm, px: Double, columns: Int, rows: Int): ByteArray {
        val left = area.left * px
        val top = area.top * px
        val cellW = area.width * px / columns
        val cellH = area.height * px / rows
        val out = ByteArray(columns * rows)
        for (r in 0 until rows) {
            val y0 = (top + r * cellH).toInt()
            val y1 = (top + (r + 1) * cellH).toInt().coerceAtLeast(y0 + 1)
            for (c in 0 until columns) {
                val x0 = (left + c * cellW).toInt()
                val x1 = (left + (c + 1) * cellW).toInt().coerceAtLeast(x0 + 1)
                var sum = 0
                var count = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    if (x < sheet.width && y < sheet.height) {
                        sum += 255 - sheet[x, y]
                        count++
                    }
                }
                out[r * columns + c] = (if (count > 0) sum / count else 0).toByte()
            }
        }
        return out
    }

    /** Normalised cross-correlation, taking the best of a few small shifts to allow for jitter. */
    private fun bestCorrelation(a: Patch, b: Patch): Double {
        var best = -1.0
        for (dy in -SHIFT_CELLS..SHIFT_CELLS) for (dx in -SHIFT_CELLS..SHIFT_CELLS) {
            val score = correlation(a, b, dx, dy)
            if (score > best) best = score
        }
        return best
    }

    private fun correlation(a: Patch, b: Patch, dx: Int, dy: Int): Double {
        var n = 0
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0
        for (r in 0 until a.rows) {
            val r2 = r + dy
            if (r2 < 0 || r2 >= a.rows) continue
            for (c in 0 until a.columns) {
                val c2 = c + dx
                if (c2 < 0 || c2 >= a.columns) continue
                val va = (a.cells[r * a.columns + c].toInt() and 0xFF).toDouble()
                val vb = (b.cells[r2 * a.columns + c2].toInt() and 0xFF).toDouble()
                sumA += va
                sumB += vb
                sumAA += va * va
                sumBB += vb * vb
                sumAB += va * vb
                n++
            }
        }
        if (n == 0) return -1.0
        val denominator = sqrt((n * sumAA - sumA * sumA) * (n * sumBB - sumB * sumB))
        return if (denominator < 1e-6) 0.0 else (n * sumAB - sumA * sumB) / denominator
    }

    private fun serialize(patches: List<Patch>): ByteArray {
        val size = 1 + patches.sumOf { 3 + it.cells.size }
        val out = ByteArray(size)
        out[0] = patches.size.toByte()
        var at = 1
        patches.forEach { p ->
            out[at++] = p.columns.toByte()
            out[at++] = p.rows.toByte()
            out[at++] = if (p.isComment) 1 else 0
            p.cells.copyInto(out, at)
            at += p.cells.size
        }
        return out
    }

    private fun deserialize(data: ByteArray): List<Patch> {
        if (data.isEmpty()) return emptyList()
        val out = ArrayList<Patch>()
        var at = 1
        repeat(data[0].toInt() and 0xFF) {
            val columns = data[at++].toInt() and 0xFF
            val rows = data[at++].toInt() and 0xFF
            val isComment = data[at++].toInt() == 1
            val cells = data.copyOfRange(at, at + columns * rows)
            at += cells.size
            out += Patch(columns, rows, cells, isComment)
        }
        return out
    }
}
