package dev.glasses.assistant.hands

import kotlin.math.abs
import kotlin.math.hypot

/** Something a hand gesture asks for. */
sealed interface HandAction {
    data object NextTrack : HandAction
    data object PreviousTrack : HandAction
    data object PlayPause : HandAction
    data object NextDisplay : HandAction
    data class Zoom(val factor: Float) : HandAction          // multiply the current zoom
    data class Turn(val dYaw: Float, val dPitch: Float) : HandAction
}

/** One camera frame's worth of hand: MediaPipe's gesture name and the 21 landmarks (x, y in 0..1). */
class HandFrame(val gesture: String, val score: Float, val x: FloatArray, val y: FloatArray, val timeMs: Long)

/**
 * Turns a stream of hand frames into actions. Plain Kotlin, so it can be tested without a phone.
 * Frames must be from the user's point of view (front camera mirrored), so "right" is their right.
 *
 *   ✌  peace sign, held about half a second   -> next display mode
 *   👍 thumbs up, held about half a second    -> play / pause
 *   🖐 open hand, swiped right or left         -> next or previous track
 *   🤏 thumb and index out, other fingers down -> zoom: spread them to zoom in, close to zoom out
 *   ✊ fist, moved around                      -> turn the 3D model
 */
class GestureEngine {
    private var holdName = ""
    private var holdSince = 0L
    private var holdFired = false

    private val palm = ArrayDeque<Pair<Long, Float>>()   // (time, wrist x) while the hand is open
    private var swipeQuietUntil = 0L

    private var pinchBase = 0f
    private var pinchLast = 0f

    private var fistX = Float.NaN
    private var fistY = Float.NaN

    /** What the engine thinks the hand is doing, for the app's status line. */
    var lastSeen = "no hand"
        private set

    fun noHand() {
        holdName = ""; holdFired = false
        palm.clear(); pinchBase = 0f; fistX = Float.NaN
        lastSeen = "no hand"
    }

    fun feed(f: HandFrame): List<HandAction> {
        val out = mutableListOf<HandAction>()
        val pinchPose = isPinchPose(f)
        val name = if (pinchPose) "Pinch" else f.gesture
        lastSeen = name

        // Held gestures: fire once per hold.
        if (name != holdName) { holdName = name; holdSince = f.timeMs; holdFired = false }
        val held = f.timeMs - holdSince
        if (!holdFired && held >= HOLD_MS) {
            when (name) {
                "Victory" -> { out += HandAction.NextDisplay; holdFired = true }
                "Thumb_Up" -> { out += HandAction.PlayPause; holdFired = true }
            }
        }

        // Swipes with an open hand.
        if (name == "Open_Palm") {
            palm.addLast(f.timeMs to f.x[WRIST])
            while (palm.isNotEmpty() && f.timeMs - palm.first().first > SWIPE_WINDOW_MS) palm.removeFirst()
            if (f.timeMs >= swipeQuietUntil && palm.size >= 3) {
                val dx = palm.last().second - palm.first().second
                if (abs(dx) >= SWIPE_DISTANCE) {
                    out += if (dx > 0) HandAction.NextTrack else HandAction.PreviousTrack
                    swipeQuietUntil = f.timeMs + SWIPE_COOLDOWN_MS
                    palm.clear()
                }
            }
        } else palm.clear()

        // Pinch to zoom: compare the thumb-index spread to where it was when the pose began.
        if (pinchPose) {
            val spread = spread(f)
            if (pinchBase == 0f) { pinchBase = spread; pinchLast = spread }
            else {
                val ratio = spread / pinchLast
                if (abs(ratio - 1f) >= ZOOM_STEP) {
                    out += HandAction.Zoom(ratio.coerceIn(0.5f, 2f))
                    pinchLast = spread
                }
            }
        } else pinchBase = 0f

        // Fist: move the hand to turn the model.
        if (name == "Closed_Fist") {
            val wx = f.x[WRIST]; val wy = f.y[WRIST]
            if (!fistX.isNaN()) {
                val dx = wx - fistX; val dy = wy - fistY
                if (abs(dx) > 0.004f || abs(dy) > 0.004f) out += HandAction.Turn(dx * 360f, dy * 180f)
            }
            fistX = wx; fistY = wy
        } else fistX = Float.NaN

        return out
    }

    /** Thumb-to-index-tip distance, measured in hand sizes so it doesn't change with distance to the camera. */
    private fun spread(f: HandFrame): Float {
        val size = dist(f, WRIST, MIDDLE_MCP).coerceAtLeast(1e-3f)
        return dist(f, THUMB_TIP, INDEX_TIP) / size
    }

    private fun extended(f: HandFrame, tip: Int, pip: Int) = dist(f, WRIST, tip) > dist(f, WRIST, pip) * 1.15f

    /** Index finger out; middle, ring and little fingers folded. */
    private fun isPinchPose(f: HandFrame): Boolean =
        f.x.size >= 21 && extended(f, INDEX_TIP, INDEX_PIP) &&
            !extended(f, MIDDLE_TIP, MIDDLE_PIP) && !extended(f, RING_TIP, RING_PIP) && !extended(f, PINKY_TIP, PINKY_PIP)

    private fun dist(f: HandFrame, a: Int, b: Int) = hypot(f.x[a] - f.x[b], f.y[a] - f.y[b])

    companion object {
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_PIP = 6; const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9; const val MIDDLE_PIP = 10; const val MIDDLE_TIP = 12
        const val RING_PIP = 14; const val RING_TIP = 16
        const val PINKY_PIP = 18; const val PINKY_TIP = 20

        const val HOLD_MS = 500L
        const val SWIPE_WINDOW_MS = 450L
        const val SWIPE_DISTANCE = 0.22f
        const val SWIPE_COOLDOWN_MS = 900L
        const val ZOOM_STEP = 0.06f
    }
}
