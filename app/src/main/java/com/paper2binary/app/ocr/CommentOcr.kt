package com.paper2binary.app.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Produces a first draft of a handwritten comment, entirely on the phone.
 *
 * Handwriting recognition is only ever a draft: it does well on neat English and cannot read
 * Bengali at all. The original image is therefore always kept next to the text, and a person
 * confirms or corrects it on the comments screen.
 */
object CommentOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The recognised text, or null if nothing could be read. Failure is not an error here. */
    suspend fun readDraft(image: File): String? {
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(image.path) } ?: return null
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val text = result.text.replace('\n', ' ').trim()
                    continuation.resume(text.ifEmpty { null })
                }
                .addOnFailureListener { continuation.resume(null) }
        }
    }
}
