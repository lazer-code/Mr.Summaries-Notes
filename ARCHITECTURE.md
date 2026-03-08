# Interactive Ink — System Architecture

This document describes the high-level architecture of the Interactive Ink
note-taking module. The design is intended to be the foundational layer for
a MyScript Notes / Nebo-class Android application that supports real-time
active-stylus input, collaborative editing, and ML-powered handwriting
recognition.

---

## Architecture Diagram

```mermaid
flowchart TD
    subgraph Android_Device["Android Device"]
        Stylus["Active Stylus\n(hardware / BT)"]
        MotionEvents["MotionEvent stream\n(touch, stylus, finger)"]
        Stylus -->|USB/BT HID| MotionEvents
    end

    subgraph Compose_UI["Jetpack Compose UI Layer"]
        InkCanvasScreen["InkCanvasScreen\n(Screen composable)"]
        Toolbar["InkToolbar\n(tool selector)"]
        InkCanvas["InkCanvas\n(AndroidView wrapper)"]
        InkCanvasScreen --> Toolbar
        InkCanvasScreen --> InkCanvas
    end

    subgraph AndroidInk["Android Ink API (androidx.ink)"]
        IPSV["InProgressStrokesView\n(front-buffered renderer)"]
        MotionPred["MotionEventPredictor\n(motion prediction)"]
        StylusTelem["StylusMotionEventHelper\n(pressure / tilt / orientation)"]
        IPSV --> MotionPred
        IPSV --> StylusTelem
    end

    subgraph ViewModel_Layer["ViewModel Layer"]
        InkViewModel["InkViewModel\n(state + business logic)"]
        CommittedStrokes["CommittedStrokeList\n(in-memory stroke store)"]
        InkViewModel --> CommittedStrokes
    end

    subgraph Gesture_Layer["Gesture Classification"]
        TouchRouter["TouchRouter\n(stylus vs finger vs palm)"]
        ScratchOutClassifier["ScratchOutClassifier\n(deletion gesture detection)"]
        ScratchOutHandler["ScratchOutDeletionHandler\n(applies deletion + reflow)"]
        TouchRouter --> ScratchOutClassifier
        ScratchOutClassifier --> ScratchOutHandler
        ScratchOutHandler --> CommittedStrokes
    end

    subgraph ML_Layer["ML Recognition (future)"]
        HWR["Handwriting Recognition\n(on-device ONNX / MLKit)"]
        TextReflow["Text Reflow Engine"]
        HWR --> TextReflow
    end

    subgraph Sync_Layer["CRDT Sync (future)"]
        CRDT["CRDT Document\n(Automerge / Yjs port)"]
        NetworkTransport["WebSocket / WebRTC transport"]
        CRDT --> NetworkTransport
    end

    subgraph Storage_Layer["Storage (future)"]
        LocalDB["Room Database\n(stroke blobs + metadata)"]
        CloudStorage["Cloud Storage\n(GCS / S3)"]
        LocalDB --> CloudStorage
    end

    %% Data flow
    MotionEvents --> InkCanvas
    InkCanvas --> IPSV
    IPSV -->|onStrokeFinished callback| InkViewModel
    InkViewModel -->|committed stroke| TouchRouter
    ScratchOutHandler -->|deletion event| InkViewModel
    InkViewModel -->|strokes| HWR
    InkViewModel -->|stroke ops| CRDT
    InkViewModel -->|persist| LocalDB
```

---

## Layer Descriptions

### Android Ink API
The `androidx.ink` library provides the `InProgressStrokesView`, a
`SurfaceView`-based component that renders strokes into a *front buffer*
during the current frame so that stylus input appears with near-zero
latency. `MotionEventPredictor` adds speculative future touch points to
reduce the visual lag caused by processing pipelines.

### Jetpack Compose UI
`InkCanvas` is a thin `AndroidView` wrapper around `InProgressStrokesView`
that exposes a Compose-friendly API. `InkCanvasScreen` composes the canvas
with a minimal toolbar and wires up the `InkViewModel`.

### ViewModel
`InkViewModel` is the single source of truth for the current set of
committed (finished) strokes. It bridges the Android Ink API callbacks and
all downstream consumers (gesture classifier, ML, CRDT, storage).

### Gesture Classification
`TouchRouter` separates stylus pen events from finger and palm touches.
`ScratchOutClassifier` analyses a completed stroke's geometry (length,
direction reversals, bounding-box aspect ratio) to determine whether the
user intends to delete underlying content. `ScratchOutDeletionHandler`
locates strokes whose bounding boxes intersect the scratch-out gesture and
removes them from the committed list, then signals the ML layer to reflow
surrounding text.

### ML Recognition *(future)*
An on-device handwriting recognition model converts committed ink strokes
to Unicode text. The recogniser runs asynchronously so it never blocks
rendering.

### CRDT Sync *(future)*
A Conflict-free Replicated Data Type (CRDT) document tracks all stroke
operations (add / delete / transform) so that multiple users can edit the
same note simultaneously without conflicts.

### Storage *(future)*
Committed strokes are serialised (protobuf / flatbuffers) and stored in a
Room database for offline access. A background sync job replicates the
local database to cloud storage.
