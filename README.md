# Sprint Timing

Native Android sprint timing MVP for a Google Pixel 7 start camera, OnePlus Nord 2T finish camera, and a receive-only pad results display.

The app uses Camera2 source-frame timestamps, a three-zone luma line-crossing detector, an affine GNSS-to-elapsed-realtime clock model, and four-timestamp UDP peer synchronization. The Pixel defaults to the START role and the OnePlus defaults to FINISH. Results are rejected when the GNSS model is not sufficiently qualified.

## Use

1. Put both phones on the same Wi-Fi network or hotspot and enable Location/GPS.
2. Open **Sprint Timing** outdoors with a clear view of the sky and wait for GNSS to become `LOCKED` or `DEGRADED`.
3. Aim each phone across its timing line, confirm the roles, and tap **ARM** on both.
4. Cross the start line and then the finish line. Only timestamps and synchronization packets are exchanged; video stays on each phone.

For setup and indoor testing, tap **MANUAL TRIGGER** on START and then FINISH. If GNSS is unavailable, manual mode visibly falls back to the measured two-way Wi-Fi clock relationship and includes its uncertainty in the result. Camera-triggered production runs remain GNSS-gated.

The front camera is selected by default. Use **CAMERA: FRONT/BACK** to switch Camera2 sessions before arming; switching automatically disarms the detector.

On other Android devices, including the paired pad, the same APK automatically enters `DISPLAY` mode. It requests no camera or location permissions, never participates in clock synchronization, and only listens for run state and final result broadcasts.

The installable debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Engineering references

The `android-docs` directory contains the imported and project-specific engineering guidance used by this implementation.

### Android skills

Native Android/Kotlin skills selected for the two-phone sprint-timing application.

## Imported foundation

- **android-cli** — official Android project/device CLI workflows.
- **android-kotlin-concurrency** — structured concurrency, bounded pipelines, Flow, cancellation, and dispatcher strategy.
- **android-performance** — evidence-driven startup, jank, memory, ANR, battery, and thermal work.
- **perfetto-trace-analysis** — official trace investigation workflow and SQL references.
- **testing-setup** — official native Android test-strategy and infrastructure setup.

## Project-specific timing skills

- **android-camera2-timing** — raw Camera2 source timestamps, YUV acquisition, high-FPS qualification, and cadence evidence.
- **android-gnss-clock-model** — affine elapsed-realtime-to-GPS model with uncertainty and discontinuity handling.
- **android-two-way-clock-sync** — repeated four-timestamp UDP synchronization and GNSS cross-checking.
- **android-roi-line-crossing-detector** — deterministic luma ROI finish-line event detection and interpolation.
- **sprint-timing-hardware-qualification** — Pixel 7 and OnePlus Nord 2T physical-device acceptance workflow.

## Important boundary

Emulators are suitable for UI, state, navigation, and failure-flow tests. Camera timestamp, GNSS clock, Wi-Fi offset, thermal, and end-to-end timing claims require physical-device evidence.

## Provenance and licenses

See [SOURCE.json](SOURCE.json) and [licenses](licenses/). Imported files retain their upstream terms. Project-specific skills were authored for this repository using official Android API references listed in each skill.
