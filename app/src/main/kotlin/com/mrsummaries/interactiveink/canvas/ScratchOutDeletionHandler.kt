package com.mrsummaries.interactiveink.canvas

import androidx.ink.strokes.Stroke

/**
 * ScratchOutDeletionHandler
 *
 * Applies a scratch-out deletion gesture to the current list of committed
 * strokes and returns the resulting state.
 *
 * ## Responsibilities
 *
 * 1. **Bounding-box intersection** — For each [CommittedStroke] in the working
 *    set, compute a [BoundingBox] from the stroke's ink points and test whether
 *    it overlaps the scratch-out gesture's bounding box. Any stroke that overlaps
 *    is considered "scratched out".
 *
 * 2. **Deletion** — Returns the subset of stroke IDs that should be removed and,
 *    if requested, the post-deletion stroke list.
 *
 * 3. **Reflow signalling** — Exposes a [ReflowRequest] that callers (typically
 *    [InkViewModel]) can forward to the ML recognition module to update text
 *    regions affected by the deletion. This is a stub for the foundational phase;
 *    the actual ML integration is out of scope.
 *
 * ## Immutability contract
 *
 * [ScratchOutDeletionHandler] is **not** stateful; it operates on the snapshot
 * of [initialStrokes] provided at construction time. [InkViewModel] is responsible
 * for applying the returned changes to the live [StateFlow].
 *
 * ## CRDT integration point
 *
 * Each deletion should be modelled as a CRDT `remove` operation keyed by the
 * stable [CommittedStroke.id]. When the CRDT layer is integrated, [InkViewModel]
 * can forward the set returned by [findIntersectingStrokes] directly to the CRDT
 * document manager.
 *
 * @param initialStrokes  The complete list of [CommittedStroke]s at the time the
 *                        scratch-out gesture was recognised.
 */
class ScratchOutDeletionHandler(
    private val initialStrokes: List<CommittedStroke>,
) {

    /**
     * Finds all strokes whose bounding boxes intersect [scratchBounds].
     *
     * Strokes are included if *any* part of their bounding box overlaps the
     * scratch-out gesture's bounding box. This is intentionally conservative:
     * a stroke that only barely overlaps is still considered "scratched out",
     * matching user expectations for a deletion gesture.
     *
     * @param scratchBounds  The [BoundingBox] of the completed scratch-out gesture,
     *                       as produced by [ScratchOutClassifier.classify].
     * @return               The stable [CommittedStroke.id] values of all strokes
     *                       that should be deleted.
     */
    fun findIntersectingStrokes(scratchBounds: BoundingBox): Set<String> {
        val result = mutableSetOf<String>()
        for (committed in initialStrokes) {
            val strokeBounds = committed.stroke.computeBoundingBox() ?: continue
            if (strokeBounds.intersects(scratchBounds)) {
                result.add(committed.id)
            }
        }
        return result
    }

    /**
     * Returns the list of [CommittedStroke]s that remain after removing the
     * strokes identified by [strokeIdsToDelete].
     *
     * This convenience function lets callers obtain the post-deletion state
     * without needing to filter [initialStrokes] themselves.
     *
     * @param strokeIdsToDelete  Set of IDs returned by [findIntersectingStrokes].
     * @return                   A new list containing only the surviving strokes,
     *                           in their original insertion order.
     */
    fun applyDeletion(strokeIdsToDelete: Set<String>): List<CommittedStroke> =
        initialStrokes.filterNot { it.id in strokeIdsToDelete }

    /**
     * Builds a [ReflowRequest] for the strokes that will be deleted.
     *
     * The request captures the union bounding box of all deleted strokes so
     * that the ML recognition module can re-analyse only the affected text region
     * rather than the entire page — important for performance on large notes.
     *
     * Returns `null` if no strokes are identified for deletion.
     *
     * @param strokeIdsToDelete  Set of IDs returned by [findIntersectingStrokes].
     */
    fun buildReflowRequest(strokeIdsToDelete: Set<String>): ReflowRequest? {
        if (strokeIdsToDelete.isEmpty()) return null

        val deletedStrokes = initialStrokes.filter { it.id in strokeIdsToDelete }
        val boxes = deletedStrokes.mapNotNull { it.stroke.computeBoundingBox() }
        if (boxes.isEmpty()) return null

        val unionBox = boxes.reduce { acc, box ->
            BoundingBox(
                left = minOf(acc.left, box.left),
                top = minOf(acc.top, box.top),
                right = maxOf(acc.right, box.right),
                bottom = maxOf(acc.bottom, box.bottom),
            )
        }

        return ReflowRequest(
            affectedRegion = unionBox,
            deletedStrokeIds = strokeIdsToDelete,
            survivingStrokes = applyDeletion(strokeIdsToDelete),
        )
    }
}

// ---------------------------------------------------------------------------
// Supporting data structures
// ---------------------------------------------------------------------------

/**
 * ReflowRequest
 *
 * Carries the information needed by the ML text-reflow engine to update the
 * recognised text after a deletion. Passed from [ScratchOutDeletionHandler]
 * to [InkViewModel] and onward to the (future) ML module.
 *
 * @property affectedRegion   The union bounding box of all deleted strokes.
 *                            The recogniser should re-analyse all strokes that
 *                            overlap or adjoin this region.
 * @property deletedStrokeIds The stable IDs of strokes that were deleted.
 * @property survivingStrokes The post-deletion stroke list. Provided here so
 *                            the recogniser does not need to query the ViewModel
 *                            asynchronously (avoids a race condition where new
 *                            strokes might be added between the deletion and the
 *                            recognition pass).
 */
data class ReflowRequest(
    val affectedRegion: BoundingBox,
    val deletedStrokeIds: Set<String>,
    val survivingStrokes: List<CommittedStroke>,
)

// ---------------------------------------------------------------------------
// Extension: Stroke bounding-box computation
// ---------------------------------------------------------------------------

/**
 * Computes the axis-aligned [BoundingBox] that tightly encloses all ink points
 * in this [Stroke].
 *
 * Returns `null` if the stroke contains no points (e.g. a cancelled stroke that
 * was committed with zero ink).
 *
 * ## Implementation note
 *
 * The Android Ink API's [Stroke] object provides access to individual
 * [StrokeInput] (raw motion) points via [Stroke.inputs]. We iterate all inputs
 * and find the min/max x and y values.
 *
 * When the Ink API exposes a native bounding-rect helper in a future release this
 * extension can be removed without changing callers.
 */
fun Stroke.computeBoundingBox(): BoundingBox? {
    // StrokeInputBatch contains the raw sampled inputs for the stroke.
    val inputs = this.inputs
    if (inputs.size == 0) return null

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    for (i in 0 until inputs.size) {
        val x = inputs.getX(i)
        val y = inputs.getY(i)
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    return BoundingBox(minX, minY, maxX, maxY)
}
