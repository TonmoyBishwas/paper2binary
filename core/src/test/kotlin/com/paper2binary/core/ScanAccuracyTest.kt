package com.paper2binary.core

import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.QuestionSpec
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.omr.MarkReader
import com.paper2binary.core.omr.ReadingStatus
import com.paper2binary.core.pipeline.ScanOutcome
import com.paper2binary.core.pipeline.SheetScan
import com.paper2binary.core.pipeline.SheetScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import kotlin.random.Random

/**
 * End-to-end accuracy against simulated phone photos with known answers. A *silent* error (the
 * app confidently saving a wrong answer) is the failure that matters most: a flagged sheet only
 * costs a person a few seconds, while a silent error corrupts the survey results.
 */
class ScanAccuracyTest {
    private val spec = SheetSpec(
        "K7M2QX",
        listOf(4, 4, 5, 3, 4, 2).map { QuestionSpec(it) },
        hasComment = true,
    )
    private val layout = SheetLayout.compute(spec)
    private val scanner = SheetScanner()

    private fun copy(image: BufferedImage): BufferedImage {
        val out = BufferedImage(image.width, image.height, image.type)
        val g = out.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return out
    }

    private val blankForm = SyntheticSheet.printedForm(spec)

    /** Draws answers on a fresh form. A value of -1 leaves the question blank. */
    private fun fill(
        answers: List<Int>,
        random: Random,
        pen: SyntheticSheet.Pen = SyntheticSheet.Pen(),
        comment: Boolean = false,
    ): BufferedImage {
        val sheet = copy(blankForm)
        answers.forEachIndexed { q, a ->
            if (a >= 0) SyntheticSheet.drawTick(sheet, layout.questions[q].options[a].box, pen, random)
        }
        if (comment) SyntheticSheet.drawComment(sheet, layout.comment!!, pen, random)
        return sheet
    }

    private fun randomAnswers(random: Random) = spec.questions.map { q ->
        if (random.nextInt(12) == 0) -1 else random.nextInt(q.optionCount)
    }

    private data class Tally(var sheets: Int = 0, var perfect: Int = 0, var flagged: Int = 0, var silentErrors: Int = 0, var failed: Int = 0)

    private fun runBatch(
        count: Int,
        seedBase: Int,
        camera: (Random) -> SyntheticSheet.Camera,
        pen: SyntheticSheet.Pen = SyntheticSheet.Pen(),
    ): Tally {
        val tally = Tally()
        for (seed in 0 until count) {
            val random = Random(seedBase + seed)
            val answers = randomAnswers(random)
            val sheet = fill(answers, random, pen)
            val photo = SyntheticSheet.photograph(sheet, camera(random), random)
            tally.sheets++
            val outcome = scanner.scan(photo)
            if (outcome !is ScanOutcome.Success) {
                tally.failed++
                continue
            }
            val read = outcome.scan.readings
            val correct = read.indices.all { q ->
                read[q].selected == if (answers[q] < 0) emptyList() else listOf(answers[q])
            }
            when {
                outcome.scan.needsReview -> tally.flagged++
                correct -> tally.perfect++
                else -> {
                    tally.silentErrors++
                    println("silent error seed=${seedBase + seed} truth=$answers read=${read.map { it.selected }}")
                }
            }
        }
        println("batch $seedBase: $tally")
        return tally
    }

    @Test
    fun `ordinary hand-held photos of black ballpoint ticks`() {
        val t = runBatch(40, 1000, { r ->
            SyntheticSheet.Camera(rotationDegrees = r.nextDouble(-8.0, 8.0))
        })
        assertEquals(0, t.failed)
        assertEquals(0, t.silentErrors)
        assertTrue("too many flagged: $t", t.flagged <= 2)
    }

    @Test
    fun `sheet turned upside down or sideways`() {
        val t = runBatch(20, 2000, { r ->
            SyntheticSheet.Camera(rotationDegrees = 90.0 * r.nextInt(4) + r.nextDouble(-5.0, 5.0))
        })
        assertEquals(0, t.failed)
        assertEquals(0, t.silentErrors)
    }

    @Test
    fun `worn photocopies in poor lighting`() {
        val t = runBatch(30, 3000, { r ->
            SyntheticSheet.Camera(
                photocopied = true,
                shadow = 0.55,
                noiseSigma = 7.0,
                blurRadius = 2,
                rotationDegrees = r.nextDouble(-5.0, 5.0),
            )
        })
        assertEquals(0, t.failed)
        assertEquals(0, t.silentErrors)
    }

    @Test
    fun `lower resolution camera`() {
        val t = runBatch(20, 4000, { r ->
            SyntheticSheet.Camera(
                photoWidth = 1080,
                photoHeight = 1440,
                rotationDegrees = r.nextDouble(-5.0, 5.0),
            )
        })
        assertEquals(0, t.failed)
        assertEquals(0, t.silentErrors)
    }

    @Test
    fun `fine pale pen is still read or else flagged`() {
        val t = runBatch(20, 5000, { r ->
            SyntheticSheet.Camera(rotationDegrees = r.nextDouble(-5.0, 5.0))
        }, pen = SyntheticSheet.Pen(widthMm = 0.3, darkness = 90))
        assertEquals(0, t.failed)
        assertEquals(0, t.silentErrors)
    }

    @Test
    fun `two ticks on a single-choice question are flagged, not guessed`() {
        val random = Random(11)
        val sheet = fill(listOf(0, 1, 2, 0, 3, 1), random)
        SyntheticSheet.drawTick(sheet, layout.questions[2].options[4].box, SyntheticSheet.Pen(), random)
        val outcome = scanner.scan(SyntheticSheet.photograph(sheet, SyntheticSheet.Camera(), random))
        val scan = (outcome as ScanOutcome.Success).scan
        assertEquals(ReadingStatus.MULTIPLE, scan.readings[2].status)
        assertEquals(listOf(2, 4), scan.readings[2].selected)
        assertTrue(scan.needsReview)
    }

    @Test
    fun `multi-select questions accept several ticks without a flag`() {
        val multi = SheetSpec("ABCDEF", listOf(QuestionSpec(4, multiSelect = true), QuestionSpec(3)), true)
        val l = SheetLayout.compute(multi)
        val random = Random(5)
        val sheet = SyntheticSheet.printedForm(multi)
        SyntheticSheet.drawTick(sheet, l.questions[0].options[0].box, SyntheticSheet.Pen(), random)
        SyntheticSheet.drawTick(sheet, l.questions[0].options[3].box, SyntheticSheet.Pen(), random)
        SyntheticSheet.drawTick(sheet, l.questions[1].options[1].box, SyntheticSheet.Pen(), random)
        val scan = (scanner.scan(SyntheticSheet.photograph(sheet, SyntheticSheet.Camera(), random)) as ScanOutcome.Success).scan
        assertEquals(listOf(0, 3), scan.readings[0].selected)
        assertEquals(ReadingStatus.OK, scan.readings[0].status)
        assertFalse(scan.needsReview)
    }

    @Test
    fun `comment box is cropped only when written in`() {
        val random = Random(21)
        val answers = listOf(0, 1, 2, 0, 3, 1)
        val withComment = fill(answers, random, comment = true)
        val without = fill(answers, random, comment = false)
        val a = scanner.scan(SyntheticSheet.photograph(withComment, SyntheticSheet.Camera(), random)) as ScanOutcome.Success
        val b = scanner.scan(SyntheticSheet.photograph(without, SyntheticSheet.Camera(), random)) as ScanOutcome.Success
        assertNotNull(a.scan.commentImage)
        assertEquals((132 * SheetScan.PX_PER_MM).toInt(), a.scan.commentImage!!.width)
        assertNull(b.scan.commentImage)
    }

    @Test
    fun `an unfilled form is reported as blank`() {
        val random = Random(31)
        val outcome = scanner.scan(SyntheticSheet.photograph(blankForm, SyntheticSheet.Camera(), random))
        assertTrue((outcome as ScanOutcome.Success).scan.isBlank)
    }

    @Test
    fun `falls back on the expected survey when the QR code is destroyed`() {
        val random = Random(41)
        val answers = listOf(1, 2, 3, 1, 0, 0)
        val sheet = fill(answers, random)
        val g = sheet.createGraphics()
        g.color = java.awt.Color.WHITE
        val qr = SheetLayout.QR_RECT
        g.fillRect((qr.left * 12).toInt(), (qr.top * 12).toInt(), (qr.width * 12).toInt(), (qr.height * 12).toInt())
        g.dispose()
        val photo = SyntheticSheet.photograph(sheet, SyntheticSheet.Camera(rotationDegrees = 180.0), random)
        assertEquals(ScanOutcome.UnreadableCode, scanner.scan(photo))
        val outcome = scanner.scan(photo, expected = spec) as ScanOutcome.Success
        assertEquals(answers.map { listOf(it) }, outcome.scan.readings.map { it.selected })
    }

    @Test
    fun `classification rules`() {
        val r = MarkReader()
        assertEquals(ReadingStatus.BLANK, r.classify(listOf(0.0, 0.01, 0.0), false).status)
        assertEquals(ReadingStatus.OK, r.classify(listOf(0.0, 0.2, 0.0), false).status)
        assertEquals(ReadingStatus.UNSURE, r.classify(listOf(0.0, 0.03, 0.0), false).status)
        assertEquals(listOf(1), r.classify(listOf(0.0, 0.03, 0.0), false).selected)
        assertEquals(ReadingStatus.MULTIPLE, r.classify(listOf(0.2, 0.2, 0.0), false).status)
        // A speck beside a firm tick is not worth a person's time.
        assertEquals(ReadingStatus.OK, r.classify(listOf(0.3, 0.02, 0.0), false).status)
    }
}
