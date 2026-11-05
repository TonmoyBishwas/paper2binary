package com.paper2binary.core

import com.paper2binary.core.dedupe.SheetFingerprint
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.QuestionSpec
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.pipeline.ScanGate
import com.paper2binary.core.pipeline.ScanOutcome
import com.paper2binary.core.pipeline.SheetScanner
import com.paper2binary.core.vision.PageQuad
import com.paper2binary.core.vision.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DuplicateAndGateTest {
    private val spec = SheetSpec("K7M2QX", listOf(4, 4, 5, 3, 4, 2).map { QuestionSpec(it) }, true)
    private val layout = SheetLayout.compute(spec)
    private val scanner = SheetScanner()
    private val answers = listOf(0, 1, 2, 0, 3, 1)

    private fun fingerprintOf(sheetSeed: Int, photoSeed: Int, comment: Boolean = false): ByteArray {
        val hand = Random(sheetSeed)
        val sheet = SyntheticSheet.printedForm(spec)
        answers.forEachIndexed { q, a ->
            SyntheticSheet.drawTick(sheet, layout.questions[q].options[a].box, SyntheticSheet.Pen(), hand)
        }
        if (comment) SyntheticSheet.drawComment(sheet, layout.comment!!, SyntheticSheet.Pen(), hand)
        val camera = Random(photoSeed)
        val photo = SyntheticSheet.photograph(
            sheet, SyntheticSheet.Camera(rotationDegrees = camera.nextDouble(-8.0, 8.0)), camera,
        )
        return SheetFingerprint.compute((scanner.scan(photo) as ScanOutcome.Success).scan)
    }

    @Test
    fun `same sheet photographed twice is recognised, other people with same answers are not`() {
        val rescans = (0 until 12).map { i ->
            SheetFingerprint.similarity(fingerprintOf(100 + i, 1), fingerprintOf(100 + i, 2))
        }
        val different = (0 until 12).map { i ->
            SheetFingerprint.similarity(fingerprintOf(200 + i, 3), fingerprintOf(300 + i, 4))
        }
        println("rescans  min=%.3f  avg=%.3f".format(rescans.min(), rescans.average()))
        println("distinct max=%.3f  avg=%.3f".format(different.max(), different.average()))
        assertTrue("rescan missed: ${rescans.min()}", rescans.min() >= SheetFingerprint.DUPLICATE_THRESHOLD)
        assertTrue("false alarm: ${different.max()}", different.max() < SheetFingerprint.DUPLICATE_THRESHOLD)
    }

    @Test
    fun `comment handwriting strengthens the comparison`() {
        val same = SheetFingerprint.similarity(fingerprintOf(400, 5, true), fingerprintOf(400, 6, true))
        val other = SheetFingerprint.similarity(fingerprintOf(401, 7, true), fingerprintOf(402, 8, true))
        println("comments same=%.3f other=%.3f".format(same, other))
        assertTrue(same >= SheetFingerprint.DUPLICATE_THRESHOLD)
        assertTrue(other < SheetFingerprint.DUPLICATE_THRESHOLD)
    }

    @Test
    fun `sheets with no writing are never called duplicates`() {
        val blank = SheetSpec("K7M2QX", listOf(4, 4).map { QuestionSpec(it) }, true)
        val photo = SyntheticSheet.photograph(SyntheticSheet.printedForm(blank), SyntheticSheet.Camera(), Random(1))
        val fp = SheetFingerprint.compute((scanner.scan(photo) as ScanOutcome.Success).scan)
        assertEquals(0.0, SheetFingerprint.similarity(fp, fp), 0.0)
    }

    @Test
    fun `answer key is stable regardless of tick order`() {
        assertEquals("1+2|-|0", SheetFingerprint.answerKey(listOf(listOf(2, 1), emptyList(), listOf(0))))
    }

    private fun quad(shift: Double = 0.0) = PageQuad(
        listOf(
            Point(100 + shift, 100.0), Point(900 + shift, 100.0),
            Point(900 + shift, 1300.0), Point(100 + shift, 1300.0),
        ),
        60.0,
    )

    @Test
    fun `gate captures only once the sheet is still`() {
        val gate = ScanGate(stableMillis = 500)
        assertEquals(ScanGate.Phase.HOLD_STILL, gate.onFrame(quad(), 1600, 0))
        assertEquals(ScanGate.Phase.HOLD_STILL, gate.onFrame(quad(40.0), 1600, 400))
        assertEquals(ScanGate.Phase.HOLD_STILL, gate.onFrame(quad(40.0), 1600, 800))
        assertEquals(ScanGate.Phase.CAPTURE, gate.onFrame(quad(40.0), 1600, 950))
    }

    @Test
    fun `gate will not capture the same sheet twice while it stays in view`() {
        val gate = ScanGate(stableMillis = 100, removalMillis = 200)
        gate.onFrame(quad(), 1600, 0)
        assertEquals(ScanGate.Phase.CAPTURE, gate.onFrame(quad(), 1600, 150))
        gate.onCaptureStarted()
        assertEquals(ScanGate.Phase.BUSY, gate.onFrame(quad(), 1600, 200))
        gate.onCaptureFinished(accepted = true, nowMillis = 300)

        for (t in 300L..5000L step 100) {
            assertEquals(ScanGate.Phase.REMOVE_SHEET, gate.onFrame(quad(), 1600, t))
        }
        // Briefly lost detection (a flicker) must not re-open the gate...
        assertEquals(ScanGate.Phase.REMOVE_SHEET, gate.onFrame(null, 1600, 5100))
        assertEquals(ScanGate.Phase.REMOVE_SHEET, gate.onFrame(quad(), 1600, 5150))
        // ...but truly removing the sheet does.
        gate.onFrame(null, 1600, 5200)
        assertEquals(ScanGate.Phase.SEARCHING, gate.onFrame(null, 1600, 5450))
        assertEquals(ScanGate.Phase.HOLD_STILL, gate.onFrame(quad(), 1600, 5500))
    }

    @Test
    fun `gate retries after an unreadable capture`() {
        val gate = ScanGate(stableMillis = 100)
        gate.onFrame(quad(), 1600, 0)
        gate.onFrame(quad(), 1600, 150)
        gate.onCaptureStarted()
        gate.onCaptureFinished(accepted = false, nowMillis = 200)
        assertEquals(ScanGate.Phase.HOLD_STILL, gate.onFrame(quad(), 1600, 250))
        assertEquals(ScanGate.Phase.CAPTURE, gate.onFrame(quad(), 1600, 400))
    }

    @Test
    fun `gate asks to move closer when the sheet is small`() {
        val gate = ScanGate()
        assertEquals(ScanGate.Phase.TOO_FAR, gate.onFrame(quad(), 4000, 0))
    }
}
