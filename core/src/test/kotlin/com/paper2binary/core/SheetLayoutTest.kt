package com.paper2binary.core

import com.paper2binary.core.layout.RectMm
import com.paper2binary.core.layout.SheetLayout
import com.paper2binary.core.model.QuestionSpec
import com.paper2binary.core.model.SheetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLayoutTest {
    private fun spec(vararg counts: Int, comment: Boolean = true) =
        SheetSpec("K7M2QX", counts.map { QuestionSpec(it) }, comment)

    @Test
    fun `typical survey fits on half A4 with comment box`() {
        val layout = SheetLayout.compute(spec(4, 4, 5, 3, 4, 2))
        assertTrue(layout.fits)
        assertNotNull(layout.comment)
        assertEquals(22, layout.allBoxes.size)
    }

    @Test
    fun `too many questions are reported instead of overlapping`() {
        val layout = SheetLayout.compute(spec(*IntArray(14) { 5 }))
        assertFalse(layout.fits)
    }

    @Test
    fun `no printed element overlaps another`() {
        val layout = SheetLayout.compute(spec(2, 3, 4, 5, 6, 8))
        assertTrue(layout.fits)
        val boxes = layout.allBoxes
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                assertFalse("boxes $i and $j overlap", boxes[i].intersects(boxes[j]))
            }
        }
        val comment = layout.comment!!
        boxes.forEach { box ->
            assertFalse(box.intersects(comment))
            assertFalse(box.intersects(SheetLayout.QR_RECT))
            assertTrue(box.left >= 0 && box.right <= SheetLayout.SHEET_WIDTH)
        }
    }

    @Test
    fun `layout is deterministic for printer and scanner`() {
        val a = SheetLayout.compute(spec(4, 3, 5))
        val b = SheetLayout.compute(spec(4, 3, 5))
        assertEquals(a.allBoxes, b.allBoxes)
    }

    @Test
    fun `spec round trips through the QR payload`() {
        val spec = SheetSpec(
            "K7M2QX",
            listOf(QuestionSpec(4), QuestionSpec(3, multiSelect = true), QuestionSpec(5)),
            hasComment = false,
        )
        assertEquals("P2B1:K7M2QX:4.3M.5:N", spec.encode())
        assertEquals(spec, SheetSpec.decode(spec.encode()))
    }

    @Test
    fun `foreign QR codes are rejected`() {
        assertNull(SheetSpec.decode("https://example.com"))
        assertNull(SheetSpec.decode("P2B1:K7M2QX:x:C"))
        assertNull(SheetSpec.decode("P2B1:K7M2QX:4.1:C"))
        assertNull(SheetSpec.decode("P2B9:K7M2QX:4:C"))
    }

    @Test
    fun `payload for a full survey stays in the QR alphanumeric set`() {
        val payload = spec(4, 4, 5, 3, 4, 2).encode()
        val alphanumeric = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
        assertTrue(payload.all { it in alphanumeric })
    }

    private fun RectMm.intersects(o: RectMm) =
        left < o.right && o.left < right && top < o.bottom && o.top < bottom
}
