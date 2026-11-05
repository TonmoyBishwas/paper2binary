package com.paper2binary.core.vision

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.layout.RectMm

/** Thin wrapper around ZXing so the rest of the code only ever deals with [GrayImage]. */
object QrCodec {

    /** Width of the white border printed around the QR code, in modules (the standard value). */
    const val QUIET_MODULES = 4

    /** QR modules with no quiet zone; the caller places them inside a white rectangle. */
    fun encode(payload: String): BitMatrix {
        val hints = mapOf(
            // Level Q tolerates about 25% damage: stray pen marks, smudges, photocopy dropout.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 0,
        )
        return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** The decoded text, or null when no QR code can be read from the image. */
    fun decode(image: GrayImage): String? {
        val source = GrayLuminanceSource(image)
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        val binarizers = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
        )
        for (bitmap in binarizers) {
            try {
                return QRCodeReader().decode(bitmap, hints).text
            } catch (_: ReaderException) {
                // Try the next binarizer; a failure here is an expected outcome, not an error.
            }
        }
        return null
    }

    /**
     * Decodes a QR code whose position is already known exactly, skipping ZXing's own search for
     * the finder patterns. The corner markers tell us where every module must be, so each one can
     * be averaged in place. This still works on photos too blurred or noisy for the normal search.
     *
     * @param sample returns the brightness at a position in sheet millimetres
     */
    fun decodeAtKnownPosition(rect: RectMm, quietModules: Int, sample: (Double, Double) -> Int): String? {
        for (version in 1..4) {
            val modules = 17 + 4 * version
            val moduleSize = rect.width / (modules + 2 * quietModules)
            val values = IntArray(modules * modules)
            var total = 0L
            for (my in 0 until modules) for (mx in 0 until modules) {
                val cx = rect.left + (mx + quietModules + 0.5) * moduleSize
                val cy = rect.top + (my + quietModules + 0.5) * moduleSize
                val d = moduleSize * 0.22
                val v = (
                    sample(cx, cy) + sample(cx - d, cy - d) + sample(cx + d, cy - d) +
                        sample(cx - d, cy + d) + sample(cx + d, cy + d)
                    ) / 5
                values[my * modules + mx] = v
                total += v
            }
            val threshold = total / values.size
            val bits = BitMatrix(modules)
            for (my in 0 until modules) for (mx in 0 until modules) {
                if (values[my * modules + mx] < threshold) bits.set(mx, my)
            }
            try {
                return Decoder().decode(bits).text
            } catch (_: ReaderException) {
                // Wrong version or wrong orientation; try the next candidate.
            }
        }
        return null
    }

    private class GrayLuminanceSource(private val image: GrayImage) :
        LuminanceSource(image.width, image.height) {

        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            System.arraycopy(image.pixels, y * width, out, 0, width)
            return out
        }

        override fun getMatrix(): ByteArray = image.pixels.copyOf(width * height)
    }
}
