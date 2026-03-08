package com.mrsummaries.interactiveink.canvas

import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor

/**
 * InkCanvas
 *
 * A Jetpack Compose wrapper around [InProgressStrokesView] from the Android Ink API.
 *
 * Key design goals:
 *  - Front-buffered rendering: [InProgressStrokesView] draws the in-progress stroke
 *    directly into the display's front buffer, bypassing the normal Choreographer / VSYNC
 *    pipeline so that stylus input appears with near-zero latency (~1 frame or less).
 *  - Motion prediction: [MotionEventPredictor] speculatively extrapolates the stylus
 *    position one or two frames ahead, reducing the visible "gap" between the pen tip
 *    and the rendered ink at higher frame rates.
 *  - Full stylus telemetry: pressure, tilt and orientation are forwarded untouched so
 *    that the brush model can use them for variable-width / opacity strokes.
 *
 * @param modifier   Standard Compose layout modifier applied to the [AndroidView] host.
 * @param brush      The [Brush] used to render new strokes. Defaults to the stock
 *                   pressure-sensitive pen with a reasonable base size.
 * @param onStrokeFinished  Callback invoked on the main thread whenever the user lifts
 *                          the stylus and a stroke is committed. The caller (typically
 *                          [InkViewModel]) should store the stroke and schedule any
 *                          downstream processing (gesture classification, ML, CRDT).
 */
@Composable
fun InkCanvas(
    modifier: Modifier = Modifier,
    brush: Brush = defaultBrush(),
    onStrokeFinished: (strokeId: InProgressStrokeId, stroke: Stroke) -> Unit = { _, _ -> },
) {
    // CanvasStrokeRenderer draws committed strokes during the normal render pass.
    // It is created once and shared with the view factory below.
    val strokeRenderer = remember { CanvasStrokeRenderer.create() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // --- InProgressStrokesView setup ---
            // InProgressStrokesView must be the *only* SurfaceView in the hierarchy that
            // writes to the front buffer; layering multiple front-buffered surfaces is
            // unsupported and causes visual artefacts on some OEM devices.
            val inkView = InProgressStrokesView(context)

            // Register a listener so that finished strokes bubble up to the ViewModel.
            inkView.addFinishedStrokesListener(
                object : InProgressStrokesFinishedListener {
                    override fun onStrokesFinished(
                        strokes: Map<InProgressStrokeId, Stroke>,
                    ) {
                        strokes.forEach { (id, stroke) ->
                            onStrokeFinished(id, stroke)
                        }
                        // Acknowledge processing so the view can release internal buffers.
                        inkView.removeFinishedStrokes(strokes.keys)
                    }
                },
            )

            // --- MotionEventPredictor setup ---
            // Attach a predictor to the view's Display so it can synchronise prediction
            // windows with the display's refresh rate.
            val predictor = MotionEventPredictor.newInstance(inkView)

            // --- Touch / stylus routing ---
            // The outer OnTouchListener on the InProgressStrokesView feeds all MotionEvents
            // through the predictor and then forwards them to the view's own input handler.
            // Note: returning `true` from the listener consumes the event; we delegate to
            // the view so that it can manage its own gesture state.
            inkView.setOnTouchListener(
                createTouchListener(
                    inkView = inkView,
                    predictor = predictor,
                    brush = brush,
                ),
            )

            inkView
        },
    )
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Builds the [View.OnTouchListener] that feeds [MotionEvent]s into the
 * [MotionEventPredictor] and then dispatches them to [InProgressStrokesView].
 *
 * Routing logic:
 *  - [MotionEvent.TOOL_TYPE_STYLUS] / [MotionEvent.TOOL_TYPE_ERASER]: forwarded
 *    directly to the ink view for drawing / erasing.
 *  - Finger touches are forwarded as well; the [TouchRouter] in [InkCanvasScreen]
 *    may intercept them before they reach this listener for gesture recognition.
 *  - Palm / unknown tool types: consumed and discarded to prevent accidental marks.
 */
private fun createTouchListener(
    inkView: InProgressStrokesView,
    predictor: MotionEventPredictor,
    brush: Brush,
): View.OnTouchListener =
    View.OnTouchListener { _, event ->
        if (event == null) return@OnTouchListener false

        // Feed the real event into the predictor first.
        predictor.record(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                // Begin a new in-progress stroke for each pointer that is a stylus or
                // a recognised finger touch (finger strokes are lower priority and may
                // be intercepted by a gesture recogniser upstream).
                val toolType = event.getToolType(event.actionIndex)
                if (toolType == MotionEvent.TOOL_TYPE_UNKNOWN ||
                    toolType == MotionEvent.TOOL_TYPE_PALM
                ) {
                    return@OnTouchListener true // discard palm / unknown
                }
                inkView.startStroke(event = event, pointerId = event.getPointerId(event.actionIndex), brush = brush)
            }

            MotionEvent.ACTION_MOVE -> {
                // Feed the real event, then the predicted event (if any) so that the
                // renderer can speculatively extend the stroke tip.
                inkView.addToStroke(event)
                val predicted = predictor.predict()
                if (predicted != null) {
                    inkView.addToStroke(predicted)
                    predicted.recycle()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                inkView.finishStroke(event = event, pointerId = event.getPointerId(event.actionIndex))
            }

            MotionEvent.ACTION_CANCEL -> {
                inkView.cancelStroke(pointerId = event.getPointerId(0), event = event)
            }
        }
        true
    }

/**
 * Returns a sensible default [Brush] for the pressure-sensitive stock pen.
 *
 * Callers can override this by passing their own [Brush] to [InkCanvas].
 * The `size` value (16 dp-equivalent) is a reasonable starting point for
 * handwriting; it can be exposed as a user preference in the toolbar.
 */
fun defaultBrush(): Brush =
    Brush.createWithColorIntArgb(
        family = StockBrushes.pressurePenLatest,
        colorIntArgb = 0xFF1A1A2E.toInt(), // deep navy — readable on white canvas
        size = 16f,
        epsilon = 0.1f,
    )
