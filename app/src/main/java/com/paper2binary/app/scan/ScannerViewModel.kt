package com.paper2binary.app.scan

import android.app.Application
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paper2binary.app.P2BApplication
import com.paper2binary.app.data.DuplicateMatch
import com.paper2binary.app.ocr.CommentOcr
import com.paper2binary.app.util.ImageConversions
import com.paper2binary.core.dedupe.SheetFingerprint
import com.paper2binary.core.imaging.GrayImage
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.model.Survey
import com.paper2binary.core.pipeline.ScanGate
import com.paper2binary.core.pipeline.ScanOutcome
import com.paper2binary.core.pipeline.SheetScan
import com.paper2binary.core.pipeline.SheetScanner
import com.paper2binary.core.vision.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A read sheet waiting for a person to check it. */
data class PendingSheet(
    val survey: Survey,
    val scan: SheetScan,
    val fingerprint: ByteArray,
    val duplicate: DuplicateMatch?,
)

/** A short result message shown over the camera. */
data class ScanNotice(val text: String, val kind: Kind, val undoResponseId: String? = null) {
    enum class Kind { SAVED, WARNING }
}

data class ScannerState(
    val survey: Survey? = null,
    val phase: ScanGate.Phase = ScanGate.Phase.SEARCHING,
    /** Sheet corners in frame coordinates, already rotated to match the screen. */
    val corners: List<Point>? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val sessionCount: Int = 0,
    val autoSave: Boolean = true,
    val notice: ScanNotice? = null,
    /** Sheets waiting for review, oldest first. The first one is on screen. */
    val queue: List<PendingSheet> = emptyList(),
    val photosRemaining: Int = 0,
)

/**
 * Runs a scanning session: watches preview frames, triggers captures and decides for each sheet
 * whether it can be saved directly or needs a person to look at it.
 *
 * @param initialSurveyId null to accept a sheet of any survey; the first sheet then decides
 */
class ScannerViewModel(application: Application, initialSurveyId: String?) : AndroidViewModel(application) {
    private val repository = (application as P2BApplication).repository
    private val scanner = SheetScanner()
    private val gate = ScanGate()

    private val mutableState = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = mutableState.asStateFlow()

    /** Called with the capture request; set by the screen, which owns the camera. */
    var captureRequest: (() -> Unit)? = null

    /** Called on each saved sheet, to give the sound and vibration cue. */
    var onSheetSaved: (() -> Unit)? = null

    private var consecutiveFailures = 0

    init {
        if (initialSurveyId != null) {
            viewModelScope.launch {
                val survey = repository.getSurvey(initialSurveyId)
                mutableState.update { it.copy(survey = survey) }
            }
        }
    }

    fun setAutoSave(enabled: Boolean) = mutableState.update { it.copy(autoSave = enabled) }

    fun dismissNotice() = mutableState.update { it.copy(notice = null) }

    /** Analyses a preview frame. Runs on the camera's analysis thread. */
    fun onPreviewFrame(frame: FrameConverter.Frame, now: Long) {
        val quad = scanner.locate(frame.image)
        val longSide = maxOf(frame.image.width, frame.image.height)
        val paused = mutableState.value.queue.isNotEmpty() || mutableState.value.photosRemaining > 0
        val phase = synchronized(gate) {
            val current = if (paused) gate.phase else gate.onFrame(quad, longSide, now)
            if (!paused && current == ScanGate.Phase.CAPTURE) gate.onCaptureStarted()
            current
        }

        mutableState.update {
            it.copy(
                phase = phase,
                corners = quad?.corners?.map(frame::toUpright),
                frameWidth = frame.uprightWidth,
                frameHeight = frame.uprightHeight,
            )
        }
        if (!paused && phase == ScanGate.Phase.CAPTURE) captureRequest?.invoke()
    }

    /** Receives the high resolution capture. The proxy is always closed here. */
    fun onCaptured(proxy: ImageProxy) {
        val bytes = try {
            val plane = proxy.planes[0].buffer
            ByteArray(plane.remaining()).also { plane.get(it) }
        } finally {
            proxy.close()
        }
        viewModelScope.launch {
            val accepted = processPhoto(bytes, fromGallery = false)
            synchronized(gate) { gate.onCaptureFinished(accepted, System.currentTimeMillis()) }
        }
    }

    fun onCaptureFailed() {
        synchronized(gate) { gate.onCaptureFinished(accepted = false, nowMillis = System.currentTimeMillis()) }
    }

    /** Reads existing photos, e.g. pictures taken earlier with another camera. */
    fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        mutableState.update { it.copy(photosRemaining = uris.size) }
        viewModelScope.launch {
            uris.forEach { uri ->
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null) processPhoto(bytes, fromGallery = true)
                mutableState.update { it.copy(photosRemaining = it.photosRemaining - 1) }
            }
        }
    }

    /** Takes a photo all the way through. Returns true when the sheet was dealt with. */
    private suspend fun processPhoto(bytes: ByteArray, fromGallery: Boolean): Boolean {
        val active = mutableState.value.survey
        val outcome = withContext(Dispatchers.Default) {
            val gray: GrayImage = ImageConversions.decodeForScan(bytes) ?: return@withContext null
            scanner.scan(gray, active?.toSpec())
        }
        if (outcome !is ScanOutcome.Success) {
            consecutiveFailures++
            // Live capture retries by itself; only speak up when it keeps on failing.
            if (fromGallery || consecutiveFailures >= 3) {
                notice(
                    if (outcome is ScanOutcome.UnreadableCode) {
                        "Could not read the QR code. Is this sheet from this survey?"
                    } else {
                        "Sheet not found. All four corner squares must be visible, in good light."
                    },
                    ScanNotice.Kind.WARNING,
                )
            }
            return false
        }
        consecutiveFailures = 0
        handleSheet(outcome.scan)
        return true
    }

    private suspend fun handleSheet(scan: SheetScan) {
        val current = mutableState.value.survey
        if (current != null && !sameSheetStructure(current.toSpec(), scan.spec)) {
            notice("This sheet belongs to a different survey (${scan.spec.surveyId}). Not saved.", ScanNotice.Kind.WARNING)
            return
        }
        // The first sheet of an open-ended session decides which survey this is.
        val survey = current ?: repository.ensureSurveyFor(scan.spec).also { found ->
            mutableState.update { it.copy(survey = found) }
        }

        val answers = scan.readings.map { it.selected }
        val fingerprint = withContext(Dispatchers.Default) { SheetFingerprint.compute(scan) }
        val duplicate = repository.findDuplicate(survey.id, answers, fingerprint)

        val needsPerson = !mutableState.value.autoSave || scan.needsReview || scan.isBlank || duplicate != null
        if (needsPerson) {
            mutableState.update {
                it.copy(queue = it.queue + PendingSheet(survey, scan, fingerprint, duplicate))
            }
        } else {
            save(scan, answers, reviewed = false, fingerprint)
        }
    }

    private fun sameSheetStructure(a: SheetSpec, b: SheetSpec) = a == b

    /** Confirms the sheet at the front of the review queue with the answers the person chose. */
    fun confirmPending(answers: List<List<Int>>) {
        val pending = mutableState.value.queue.firstOrNull() ?: return
        mutableState.update { it.copy(queue = it.queue.drop(1)) }
        synchronized(gate) { gate.requireRemoval() }
        viewModelScope.launch {
            save(pending.scan, answers, reviewed = true, pending.fingerprint)
        }
    }

    /** Throws away the sheet at the front of the review queue. */
    fun discardPending() {
        mutableState.update { it.copy(queue = it.queue.drop(1)) }
        synchronized(gate) { gate.requireRemoval() }
    }

    private suspend fun save(scan: SheetScan, answers: List<List<Int>>, reviewed: Boolean, fingerprint: ByteArray) {
        val id = repository.saveScan(scan, answers, reviewed, fingerprint)
        val count = mutableState.value.sessionCount + 1
        mutableState.update {
            it.copy(
                sessionCount = count,
                notice = ScanNotice("Sheet $count saved", ScanNotice.Kind.SAVED, undoResponseId = id),
            )
        }
        onSheetSaved?.invoke()
        // Reading a comment takes a moment, so it happens after the person moved on.
        if (scan.commentImage != null) {
            viewModelScope.launch {
                val response = repository.getResponse(id) ?: return@launch
                val name = response.commentImage ?: return@launch
                val draft = CommentOcr.readDraft(repository.imageFile(response.surveyId, name))
                if (draft != null) repository.updateComment(id, draft, confirmed = false)
            }
        }
    }

    fun undo(responseId: String) {
        viewModelScope.launch {
            repository.deleteResponse(responseId)
            mutableState.update {
                it.copy(
                    sessionCount = (it.sessionCount - 1).coerceAtLeast(0),
                    notice = ScanNotice("Removed. Scan the sheet again.", ScanNotice.Kind.WARNING),
                )
            }
        }
    }

    private fun notice(text: String, kind: ScanNotice.Kind) {
        mutableState.update { it.copy(notice = ScanNotice(text, kind)) }
    }
}
