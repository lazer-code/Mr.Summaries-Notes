package com.mrsummaries.interactiveink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mrsummaries.interactiveink.canvas.InkCanvasScreen
import com.mrsummaries.interactiveink.ui.theme.MrSummariesNotesTheme

/**
 * MainActivity
 *
 * The single Activity for Mr.Summaries Notes. It hosts the Compose content
 * tree and serves as the root lifecycle owner for [InkViewModel].
 *
 * Design decisions:
 *  - Edge-to-edge is enabled so the ink canvas can extend behind the system
 *    bars, giving a full-bleed note-taking surface on devices that support it.
 *  - No explicit ViewModel construction here: [InkCanvasScreen] creates and
 *    retains [InkViewModel] via the `viewModel()` delegate, scoped to this
 *    Activity's [ViewModelStore].
 *
 * TODO (future phases):
 *  - Add multi-page / document navigation once the Room storage layer is ready.
 *  - Handle back-press to auto-save the current page before finishing.
 *  - Add deep-link support for opening shared note links.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // enableEdgeToEdge() must be called before super.onCreate() per AndroidX docs.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            MrSummariesNotesTheme {
                // InkCanvasScreen creates its own InkViewModel scoped to this Activity.
                // The ViewModel survives orientation changes via the ViewModelStore.
                InkCanvasScreen()
            }
        }
    }
}
