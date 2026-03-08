package com.mrsummaries.interactiveink.canvas

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * InkCanvasScreen
 *
 * Top-level screen composable that assembles:
 *  - [InkToolbar]  — minimal tool-selection row (pen / eraser / undo)
 *  - [InkCanvas]   — low-latency drawing surface
 *  - [TouchRouter] integration — intercepts finger gestures before they
 *    reach the drawing surface so that pinch-to-zoom, scratch-out, etc.
 *    can be recognised without polluting the ink layer with accidental marks.
 *
 * The screen owns no business logic; everything is delegated to [InkViewModel].
 *
 * @param viewModel  The [InkViewModel] instance; injected by Compose DI by default.
 */
@Composable
fun InkCanvasScreen(
    viewModel: InkViewModel = viewModel(),
) {
    val activeTool by viewModel.activeTool.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Toolbar ──────────────────────────────────────────────────────
            InkToolbar(
                activeTool = activeTool,
                onPenSelected = { viewModel.selectTool(InkTool.Pen) },
                onEraserSelected = { viewModel.selectTool(InkTool.Eraser) },
                onUndo = { viewModel.undo() },
            )

            // ── Canvas ───────────────────────────────────────────────────────
            // The TouchRouter intercepts MotionEvents at the Compose level.
            // Stylus events are allowed through to [InkCanvas]; finger events
            // may be consumed for gesture recognition (scratch-out, pan, zoom).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
                    // pointerInteropFilter gives us raw MotionEvents before
                    // Compose's pointer pipeline, which is necessary for the
                    // TouchRouter to distinguish stylus from finger reliably.
                    .pointerInteropFilter { event ->
                        routeMotionEvent(event, viewModel)
                    },
            ) {
                InkCanvas(
                    modifier = Modifier.fillMaxSize(),
                    brush = when (activeTool) {
                        InkTool.Pen -> defaultBrush()
                        InkTool.Eraser -> eraserBrush()
                    },
                    onStrokeFinished = { strokeId, stroke ->
                        viewModel.onStrokeFinished(strokeId, stroke)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// TouchRouter
// ---------------------------------------------------------------------------

/**
 * Routes a raw [MotionEvent] from the Compose [pointerInteropFilter] to the
 * appropriate consumer.
 *
 * Returns `true` if the event was fully consumed (Compose should not process
 * it further), or `false` to let Compose's normal pointer pipeline handle it.
 *
 * Current routing rules:
 *  - Stylus (pen / eraser) events: not consumed here; they fall through to
 *    [InkCanvas] which feeds them to [InProgressStrokesView].
 *  - Finger gestures on a scratch-out path: forwarded to [InkViewModel] for
 *    gesture classification. The event is consumed so the ink layer never
 *    sees it (preventing spurious finger-paint marks while the user gestures).
 *
 * Extensibility note:
 *  Add additional gesture recognisers here (e.g. two-finger scroll, pinch
 *  zoom) without modifying [InkCanvas] or [InkViewModel].
 */
private fun routeMotionEvent(
    event: MotionEvent,
    viewModel: InkViewModel,
): Boolean {
    // Identify the tool type of the primary pointer.
    val toolType = if (event.pointerCount > 0) {
        event.getToolType(0)
    } else {
        MotionEvent.TOOL_TYPE_UNKNOWN
    }

    return when (toolType) {
        // Stylus and eraser: let InkCanvas handle them through its own OnTouchListener.
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER,
        -> false // not consumed; propagates to InkCanvas

        // Finger: pass to ViewModel for gesture classification. If the gesture
        // is recognised as scratch-out the ViewModel will consume it and trigger
        // deletion; otherwise it's forwarded for normal handling.
        MotionEvent.TOOL_TYPE_FINGER -> {
            viewModel.onFingerMotionEvent(event)
            // Return true to consume finger events so they don't create ink marks.
            true
        }

        // Palm / unknown: consume silently to prevent accidental marks.
        else -> true
    }
}

// ---------------------------------------------------------------------------
// Toolbar
// ---------------------------------------------------------------------------

/**
 * InkToolbar
 *
 * A minimal horizontal toolbar providing:
 *  - Pen tool toggle
 *  - Eraser tool toggle
 *  - Undo button
 *
 * The toolbar is intentionally simple; a production app would replace this
 * with a richer component (colour picker, stroke-width slider, tool palette).
 *
 * @param activeTool       Currently selected [InkTool].
 * @param onPenSelected    Callback invoked when the pen button is tapped.
 * @param onEraserSelected Callback invoked when the eraser button is tapped.
 * @param onUndo           Callback invoked when the undo button is tapped.
 */
@Composable
fun InkToolbar(
    activeTool: InkTool,
    onPenSelected: () -> Unit,
    onEraserSelected: () -> Unit,
    onUndo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pen button — highlighted when active
        IconButton(
            onClick = onPenSelected,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Pen tool",
                tint = if (activeTool == InkTool.Pen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // Eraser button — highlighted when active
        IconButton(
            onClick = onEraserSelected,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Eraser tool",
                tint = if (activeTool == InkTool.Eraser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        // Undo button — always enabled while there are committed strokes
        IconButton(
            onClick = onUndo,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Undo,
                contentDescription = "Undo",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Brush helpers
// ---------------------------------------------------------------------------

/**
 * Returns a [androidx.ink.brush.Brush] configured for stroke erasure.
 *
 * Uses the stock marker brush at a larger size and fully-transparent white so
 * that erased regions reveal the canvas background. A production eraser would
 * use the dedicated eraser brush family when it becomes available in the API.
 */
fun eraserBrush(): androidx.ink.brush.Brush =
    androidx.ink.brush.Brush.createWithColorIntArgb(
        family = androidx.ink.brush.StockBrushes.markerLatest,
        colorIntArgb = 0x00FFFFFF, // transparent white
        size = 32f,
        epsilon = 0.1f,
    )
