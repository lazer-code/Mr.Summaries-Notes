package com.mrsummaries.interactiveink.canvas

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Data structures
// ---------------------------------------------------------------------------

/**
 * BoundingBox
 *
 * An axis-aligned rectangle defined by its minimum and maximum coordinates.
 * Used both to describe the spatial extent of a [CommittedStroke] and to
 * encapsulate the area swept by a scratch-out gesture.
 *
 * @property left   Left edge (minimum x).
 * @property top    Top edge (minimum y) — screen coordinates (y increases downward).
 * @property right  Right edge (maximum x).
 * @property bottom Bottom edge (maximum y).
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Width of the bounding box in pixels. */
    val width: Float get() = right - left

    /** Height of the bounding box in pixels. */
    val height: Float get() = bottom - top

    /** Aspect ratio (width / height). Returns [Float.MAX_VALUE] for zero-height boxes. */
    val aspectRatio: Float
        get() = if (height == 0f) Float.MAX_VALUE else width / height

    /**
     * Returns `true` if this box overlaps [other] by at least one pixel.
     *
     * Two boxes overlap when their horizontal and vertical projections both
     * overlap; i.e. they are *not* separated along either axis.
     */
    fun intersects(other: BoundingBox): Boolean =
        left < other.right && right > other.left &&
            top < other.bottom && bottom > other.top

    companion object {
        /**
         * Computes the [BoundingBox] that tightly contains all of the given [points].
         * Returns `null` if [points] is empty.
         */
        fun from(points: List<GesturePoint>): BoundingBox? {
            if (points.isEmpty()) return null
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return BoundingBox(minX, minY, maxX, maxY)
        }
    }
}

/**
 * GesturePoint
 *
 * A single sampled point in a finger gesture, with a timestamp so that
 * time-based features (velocity, acceleration) can be computed.
 *
 * @property x         Screen x-coordinate in pixels.
 * @property y         Screen y-coordinate in pixels.
 * @property timestamp Millisecond timestamp from [android.view.MotionEvent.eventTime].
 */
data class GesturePoint(
    val x: Float,
    val y: Float,
    val timestamp: Long,
)

/**
 * GestureEvent
 *
 * A complete gesture captured as an ordered list of [GesturePoint]s and its
 * pre-computed [BoundingBox]. Passed to downstream consumers (deletion handler,
 * ML pipeline) once the gesture has been classified.
 *
 * @property points      All sampled points in chronological order.
 * @property boundingBox Tight bounding box around [points].
 * @property durationMs  Total duration of the gesture in milliseconds.
 */
data class GestureEvent(
    val points: List<GesturePoint>,
    val boundingBox: BoundingBox,
    val durationMs: Long,
)

/**
 * GestureResult
 *
 * Sealed result type returned by [ScratchOutClassifier.classify].
 *
 * - [ScratchOut] — the gesture is a deletion gesture; [boundingBox] describes
 *   the area that should be checked for intersecting strokes.
 * - [Unknown]    — the gesture was not recognised as any known type; callers
 *   should either ignore it or pass it to another classifier.
 */
sealed class GestureResult {
    /**
     * The gesture was classified as a scratch-out deletion gesture.
     *
     * @property event       The full [GestureEvent] that triggered the classification.
     * @property boundingBox Convenience alias for [GestureEvent.boundingBox].
     * @property confidence  Confidence score in [0.0, 1.0]. Values above [ScratchOutClassifier.CONFIDENCE_THRESHOLD]
     *                       should be acted upon; lower values can be presented as "did you mean to delete?"
     */
    data class ScratchOut(
        val event: GestureEvent,
        val boundingBox: BoundingBox,
        val confidence: Float,
    ) : GestureResult()

    /** The gesture could not be classified as a known type. */
    object Unknown : GestureResult()
}

// ---------------------------------------------------------------------------
// Classifier
// ---------------------------------------------------------------------------

/**
 * ScratchOutClassifier
 *
 * Detects *scratch-out* (deletion) gestures — rapid back-and-forth strokes
 * drawn over content to signal that it should be erased — using a
 * multi-feature heuristic algorithm.
 *
 * ## Algorithm overview
 *
 * A scratch-out gesture has several distinguishing geometric features:
 *
 * 1. **Direction reversals** — the x-component of the velocity vector changes
 *    sign multiple times, creating the characteristic "zig-zag" shape.
 * 2. **High horizontal density** — the horizontal extent (width) of the gesture
 *    is significantly larger than its vertical extent (height), giving a wide,
 *    flat bounding box.
 * 3. **Minimum path length** — very short strokes (accidental touches) are
 *    filtered out by requiring a minimum arc length.
 * 4. **Minimum speed** — slow, deliberate strokes are unlikely to be scratch-outs;
 *    a minimum average speed threshold helps exclude them.
 *
 * A *confidence score* is computed from the above features and compared against
 * [CONFIDENCE_THRESHOLD]. This allows the caller to present the user with an
 * "undo deletion?" prompt for borderline cases rather than silently discarding ink.
 *
 * ## Tuning
 *
 * All thresholds are exposed as `const val` so they can be adjusted based on
 * user-study data or per-device calibration without changing the algorithm logic.
 *
 * ## Future extensibility
 *
 * - Replace the heuristic with an on-device ML model: keep [classify] as the
 *   entry point and swap the feature-extraction logic internally.
 * - Add a `ScratchOutClassifier.Builder` for dependency-injected thresholds.
 * - Support multi-stroke scratch-outs by accumulating gestures over a short
 *   time window.
 */
class ScratchOutClassifier {

    companion object {
        // ── Tunable thresholds ────────────────────────────────────────────

        /**
         * Minimum number of x-direction reversals required before a gesture is
         * considered a potential scratch-out. Fewer reversals suggest a simple
         * swipe or a single pen stroke rather than a deletion gesture.
         */
        const val MIN_DIRECTION_REVERSALS: Int = 2

        /**
         * Minimum bounding-box aspect ratio (width / height) for a scratch-out.
         * Scratch-outs tend to be wide and short; a ratio below this value is
         * more likely a vertical strike or a loop, not a horizontal scratch.
         */
        const val MIN_ASPECT_RATIO: Float = 1.5f

        /**
         * Minimum total arc length of the gesture path in pixels. Gestures
         * shorter than this are unlikely to cover enough content to be deletions.
         * Calibrate for the display density of the target device.
         */
        const val MIN_PATH_LENGTH_PX: Float = 80f

        /**
         * Minimum average speed of the gesture in pixels/ms. Very slow gestures
         * are treated as deliberate drawing rather than impatient scratching.
         */
        const val MIN_AVERAGE_SPEED_PX_MS: Float = 0.3f

        /**
         * Minimum confidence score [0.0, 1.0] required to classify a gesture as
         * a scratch-out. Scores below this threshold return [GestureResult.Unknown].
         */
        const val CONFIDENCE_THRESHOLD: Float = 0.55f
    }

    /**
     * Classifies the provided sequence of [GesturePoint]s as either a
     * [GestureResult.ScratchOut] or [GestureResult.Unknown].
     *
     * @param points  Chronologically ordered list of points sampled from a single
     *                finger gesture (all points between ACTION_DOWN and ACTION_UP).
     * @return        A [GestureResult] describing the classification outcome.
     */
    fun classify(points: List<GesturePoint>): GestureResult {
        // Require at least 3 points to compute reversals and path length.
        if (points.size < 3) return GestureResult.Unknown

        val boundingBox = BoundingBox.from(points) ?: return GestureResult.Unknown

        val pathLength = computePathLength(points)
        if (pathLength < MIN_PATH_LENGTH_PX) return GestureResult.Unknown

        val durationMs = points.last().timestamp - points.first().timestamp
        if (durationMs <= 0) return GestureResult.Unknown

        val averageSpeed = pathLength / durationMs
        if (averageSpeed < MIN_AVERAGE_SPEED_PX_MS) return GestureResult.Unknown

        val reversals = countDirectionReversals(points)
        if (reversals < MIN_DIRECTION_REVERSALS) return GestureResult.Unknown

        if (boundingBox.aspectRatio < MIN_ASPECT_RATIO) return GestureResult.Unknown

        // ── Confidence scoring ────────────────────────────────────────────
        // Each feature contributes an independent sub-score. The overall
        // confidence is the unweighted average. Future implementations can
        // replace this with a logistic regression or small neural network.

        val reversalScore = normalise(reversals.toFloat(), MIN_DIRECTION_REVERSALS.toFloat(), 8f)
        val aspectScore = normalise(boundingBox.aspectRatio, MIN_ASPECT_RATIO, 6f)
        val speedScore = normalise(averageSpeed, MIN_AVERAGE_SPEED_PX_MS, 2f)
        val lengthScore = normalise(pathLength, MIN_PATH_LENGTH_PX, 400f)

        val confidence = (reversalScore + aspectScore + speedScore + lengthScore) / 4f

        return if (confidence >= CONFIDENCE_THRESHOLD) {
            val event = GestureEvent(
                points = points,
                boundingBox = boundingBox,
                durationMs = durationMs,
            )
            GestureResult.ScratchOut(
                event = event,
                boundingBox = boundingBox,
                confidence = confidence,
            )
        } else {
            GestureResult.Unknown
        }
    }

    // ── Feature extraction helpers ────────────────────────────────────────

    /**
     * Counts the number of times the dominant motion direction (x-axis) reverses.
     *
     * A reversal is detected when consecutive x-deltas have opposite signs
     * (one positive, one negative) after smoothing out zero-length segments.
     * This avoids counting noise as real direction changes.
     */
    private fun countDirectionReversals(points: List<GesturePoint>): Int {
        var reversals = 0
        var lastDx = 0f

        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            if (abs(dx) < 1f) continue // skip sub-pixel movement (noise)
            if (lastDx != 0f && (dx > 0f) != (lastDx > 0f)) {
                reversals++
            }
            lastDx = dx
        }
        return reversals
    }

    /**
     * Computes the total arc length of the gesture path in pixels.
     *
     * Uses Euclidean distance between consecutive sample points. This
     * over-estimates the true arc length for very sparse sampling but is
     * a good approximation for the high-frequency samples typical of
     * Android touch events (≥ 60 Hz).
     */
    private fun computePathLength(points: List<GesturePoint>): Float {
        var length = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            length += sqrt(dx * dx + dy * dy)
        }
        return length
    }

    /**
     * Maps [value] from the range [[low], [high]] to [0.0, 1.0], clamped.
     *
     * Used to convert raw feature values to normalised sub-scores so they
     * can be combined into a single confidence value.
     */
    private fun normalise(value: Float, low: Float, high: Float): Float {
        if (high <= low) return 1f
        return ((value - low) / (high - low)).coerceIn(0f, 1f)
    }
}
