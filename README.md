# Sprint Timing

Native Android sprint timing MVP for a Google Pixel 7, Huawei EML-L29, and OnePlus Nord 2T camera array, plus a receive-only pad results display.

Android 10/API 29 and newer are supported. The Pixel defaults to START, Huawei EML-L29 to SPLIT, OnePlus to FINISH, and the Xiaomi pad to DISPLAY.

The app uses Camera2 source-frame timestamps, a three-zone luma line-crossing detector, an affine GNSS-to-elapsed-realtime clock model, and per-phone four-timestamp UDP synchronization. Each camera phone contributes one timestamp. The three timestamps are sorted: earliest is start, second is the split, and third is the finish. GNSS is used only when all three events have qualified GNSS mappings; otherwise the per-peer Wi-Fi clock models provide the fallback time domain.

## Use

1. Put all three camera phones and the display pad on the same Wi-Fi network or hotspot and enable Location/GPS on the phones.
2. Open **Sprint Timing** outdoors with a clear view of the sky and wait for GNSS to become `LOCKED` or `DEGRADED`.
3. Aim each phone across its timing line, confirm the roles, and tap **ARM** on both.
4. Cross the start line and then the finish line. Only timestamps and synchronization packets are exchanged; video stays on each phone.

For setup and indoor testing, tap **MANUAL TRIGGER** once on each of the three camera phones in the same order the athlete crosses them. The finish phone sorts the timestamps and publishes both split and total time. If GNSS is unavailable on any phone, the run visibly falls back to measured two-way Wi-Fi clock relationships and includes their uncertainty.

The Pixel 7 defaults to its rear camera because that measurement stream delivers 60 fps. The OnePlus defaults to its front camera. Use **CAMERA: FRONT/BACK** to switch Camera2 sessions before arming; switching automatically disarms the detector.

The Pixel 7 requests an advertised 60 fps Camera2 range; the OnePlus remains at 30 fps. The app measures delivered cadence from source-frame timestamps and displays the measured FPS rather than assuming the request succeeded.

On other Android devices, including the paired Xiaomi pad, the same APK automatically enters `DISPLAY` mode. It requests no camera or location permissions, never participates in clock synchronization, and only listens for run state and final result broadcasts.

The pad provides remote controls for all three timing phones: a shared **ARM/DISARM DEVICES** toggle, independent START, SPLIT, and FINISH sensitivity sliders, and a shared **PREVIEW ON/OFF** toggle. Sensitivity changes the luma-difference and zone-occupancy thresholds on the targeted detector. Preview OFF only makes the TextureView and timing-line overlay transparent; the Camera2 analysis surface and armed detector continue running.

Completed results are stored on the pad in a persistent, newest-first history containing time, duration, timing mode/confidence, and uncertainty.

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
