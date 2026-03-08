# Mr.Summaries Notes

An Android stylus-first note-taking app built with Jetpack Compose and the Android Ink API.
Draw with your stylus, scratch out text to delete strokes, and undo mistakes with a single tap.

> **Status**: Foundational infrastructure complete — ink canvas, gesture recognition, and ViewModel are working.  
> CRDT sync, ML/HTR handwriting recognition, and persistent storage are planned for future phases (see `ARCHITECTURE.md`).

---

## Features

- **Low-latency stylus rendering** via `InProgressStrokesView` (front-buffered, bypasses Choreographer)
- **Motion prediction** via `MotionEventPredictor` — speculative extrapolation reduces the visual gap at high frame rates
- **Pressure / tilt / orientation** forwarded to the brush model for variable-width strokes
- **Scratch-out deletion** — draw a rapid back-and-forth gesture with your finger to delete strokes underneath
- **Undo** — tap the toolbar Undo button to reverse the last add or delete action
- **Tool switching** — Pen and Eraser tools in the toolbar

---

## Requirements

| Tool | Minimum version |
|------|-----------------|
| Android Studio | Hedgehog (2023.1.1) or later |
| JDK | 17 (bundled with Android Studio) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.0 |
| Android device / emulator | API 26 (Android 8.0) |

> **Note**: The Android Ink API (`androidx.ink`) is in alpha. Full stylus telemetry (tilt, orientation) requires a physical device running Android 8.0+ with a compatible stylus. The emulator supports basic touch input but not stylus pressure.

---

## Building & Running

### 1. Clone the repository

```bash
git clone https://github.com/lazer-code/Mr.Summaries-Notes.git
cd Mr.Summaries-Notes
```

### 2. Open in Android Studio

1. Launch **Android Studio**.
2. Choose **File → Open** and select the cloned `Mr.Summaries-Notes` directory.
3. Android Studio will detect `settings.gradle.kts` and start a Gradle sync automatically.  
   On first sync it will download:
   - Gradle 8.7 distribution (~100 MB)
   - All Maven dependencies (Compose BOM, AndroidX Ink, etc.)

> If the sync fails, verify that your JDK is set to **JDK 17** in  
> **File → Project Structure → SDK Location → Gradle JDK**.

### 3. Run on a device or emulator

1. Connect an Android device (API 26+) with USB debugging enabled, **or** create an AVD via  
   **Device Manager → Create Virtual Device** (choose a tablet profile for the best stylus experience).
2. Select your device in the toolbar run target dropdown.
3. Click **Run ▶** (or press `Shift+F10`).

### 4. Command-line build (optional)

```bash
# Debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Run lint
./gradlew lint
```

> **Windows users**: use `gradlew.bat` instead of `./gradlew`.

---

## Project Structure

```
Mr.Summaries-Notes/
├── app/
│   ├── build.gradle.kts              # App module dependencies & build config
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml       # Activity declaration
│       ├── kotlin/com/mrsummaries/interactiveink/
│       │   ├── MainActivity.kt       # Compose entry point
│       │   ├── ui/theme/             # Material 3 theme (Color, Type, Theme)
│       │   └── canvas/
│       │       ├── InkCanvas.kt              # Compose wrapper around InProgressStrokesView
│       │       ├── InkCanvasScreen.kt        # Top-level screen + toolbar + touch routing
│       │       ├── InkViewModel.kt           # Committed strokes state & undo stack
│       │       ├── ScratchOutClassifier.kt   # Heuristic scratch-out gesture detection
│       │       └── ScratchOutDeletionHandler.kt  # Bounding-box intersection deletion
│       └── res/values/
│           ├── strings.xml
│           ├── colors.xml
│           └── themes.xml
├── gradle/
│   ├── libs.versions.toml            # Version catalog (single source of truth for deps)
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts                  # Root build file
├── settings.gradle.kts               # Module inclusion & repo config
├── gradle.properties
├── gradlew / gradlew.bat
└── ARCHITECTURE.md                   # High-level design & future roadmap
```

---

## Stubbed / TODO Code

The following items are intentionally deferred and marked with `TODO` comments in the source:

| Location | Description |
|----------|-------------|
| `InkViewModel.kt` | Signal ML text-reflow engine after scratch-out deletion |
| `ScratchOutDeletionHandler.kt` | `buildReflowRequest()` — stub for future ML integration |
| `MainActivity.kt` | Multi-page navigation, back-press auto-save |
| `InkCanvasScreen.kt` | Colour picker, stroke-width slider, lasso selection |

---

## Architecture

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for a full description of the layered architecture and the planned CRDT / ML / storage integrations.

---

## Contributing

1. Fork the repository and create a feature branch.
2. Make your changes, ensuring the project still builds (`./gradlew assembleDebug`).
3. Run lint and verify there are no new warnings: `./gradlew lint`.
4. Follow the existing code style — Kotlin official style guide with `kotlin.code.style=official` (enforced in `gradle.properties`).
5. Write a clear commit message in the imperative mood (e.g. `Add colour picker to InkToolbar`).
6. Open a pull request with a clear description of what you changed and why.
