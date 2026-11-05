package com.paper2binary.core.pipeline

import com.paper2binary.core.vision.PageQuad
import com.paper2binary.core.vision.distance

/**
 * Decides when the live camera should take a picture, so the person only has to swap papers.
 *
 * The important rule: after a capture, the gate stays closed until the sheet has left the view.
 * Without it, a sheet left under the phone would be counted again and again.
 */
class ScanGate(
    /** How long the sheet must stay still before capture. */
    private val stableMillis: Long = 600,
    /** Movement allowed while "still", as a fraction of the sheet's longest side. */
    private val moveTolerance: Double = 0.012,
    /** How long the sheet must be gone before the next one is accepted. */
    private val removalMillis: Long = 250,
    /** Sheet's longest side must cover at least this fraction of the frame's. */
    private val minFrameCoverage: Double = 0.4,
) {
    enum class Phase {
        /** No sheet in view. */
        SEARCHING,

        /** A sheet is in view but too small to read accurately. */
        TOO_FAR,

        /** Sheet in view; waiting for it to stop moving. */
        HOLD_STILL,

        /** Sheet is steady. The caller must capture and report back. */
        CAPTURE,

        /** Capture is running; further frames are ignored. */
        BUSY,

        /** Sheet was captured; waiting for it to be taken away. */
        REMOVE_SHEET,
    }

    var phase: Phase = Phase.SEARCHING
        private set

    private var lastCorners: List<com.paper2binary.core.vision.Point>? = null
    private var stableSince = 0L
    private var goneSince = -1L

    /** Feed the corners of each preview frame. Returns the phase for that frame. */
    fun onFrame(quad: PageQuad?, frameLongSide: Int, nowMillis: Long): Phase {
        when (phase) {
            Phase.BUSY, Phase.CAPTURE -> return phase
            Phase.REMOVE_SHEET -> {
                if (quad != null) {
                    goneSince = -1
                } else {
                    if (goneSince < 0) goneSince = nowMillis
                    if (nowMillis - goneSince >= removalMillis) phase = Phase.SEARCHING
                }
                return phase
            }
            else -> Unit
        }

        if (quad == null) {
            lastCorners = null
            phase = Phase.SEARCHING
            return phase
        }
        if (quad.longestSide < frameLongSide * minFrameCoverage) {
            lastCorners = null
            phase = Phase.TOO_FAR
            return phase
        }

        val previous = lastCorners
        val tolerance = quad.longestSide * moveTolerance
        val moved = previous == null ||
            previous.zip(quad.corners).any { (a, b) -> distance(a, b) > tolerance }
        if (moved) stableSince = nowMillis
        lastCorners = quad.corners

        phase = if (nowMillis - stableSince >= stableMillis) Phase.CAPTURE else Phase.HOLD_STILL
        return phase
    }

    /** Call when the caller starts acting on [Phase.CAPTURE]. */
    fun onCaptureStarted() {
        phase = Phase.BUSY
    }

    /** @param accepted false if the photo could not be read, in which case the gate tries again */
    fun onCaptureFinished(accepted: Boolean, nowMillis: Long) {
        lastCorners = null
        stableSince = nowMillis
        goneSince = -1
        phase = if (accepted) Phase.REMOVE_SHEET else Phase.SEARCHING
    }

    /** Re-opens the gate, e.g. after the user returns from reviewing a sheet. */
    fun requireRemoval() {
        goneSince = -1
        lastCorners = null
        phase = Phase.REMOVE_SHEET
    }
}
