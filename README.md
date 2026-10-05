# Sprint Timing

Native Android sprint timing MVP for a Google Pixel 7, Huawei EML-L29, and OnePlus Nord 2T camera array, plus a receive-only pad results display.

Android 10/API 29 and newer are supported. The Pixel defaults to START, Huawei EML-L29 to SPLIT, OnePlus to FINISH, and the Xiaomi pad to DISPLAY.

The app uses Camera2 source-frame timestamps, a leading-edge line-crossing detector with sub-frame interpolation, an affine GNSS-to-elapsed-realtime clock model, and drift-tracked four-timestamp UDP synchronization with every peer. Each camera phone contributes one timestamp. The three timestamps are sorted: earliest is start, second is the split, and third is the finish. For every run the FINISH phone computes the result in both time bases when it can (GNSS needs all three phones mapped) and reports the one with the smaller uncertainty; when both exist they cross-check each other and a disagreement caps confidence at 50 %.

## Use

1. Put all three camera phones and the display pad on the same Wi-Fi network or hotspot and enable Location/GPS on the phones.
2. Open **Sprint Timing** outdoors with a clear view of the sky. The status block shows camera cadence, GNSS state and Wi-Fi sync uncertainty.
3. Aim each phone across its timing line and **drag the green line** onto the physical mark. The shaded strip is the region the detector analyses; keep the athlete's starting position outside it.
4. Arm from the pad (**ARM ALL**) or with **ARM** on each phone. Arming locks exposure, white balance and focus and learns the background for ~0.3 s.
5. Run. Each phone disarms after its crossing; results appear on every device and in the pad history (long-press to clear).

Chips at the top of each phone select the **role** (persisted), the **camera** (front/back) and the **frame rate**. Only modes the camera actually supports are offered: normal 30/60/90/120 fps sessions and, where the camera has the constrained high-speed capability, **120 HS / 240 HS**. High-speed sessions cannot feed a CPU image reader, so those frames go through an OpenGL pipeline that renders an upright, downscaled luma frame on the GPU. If a device rejects a high-speed session the phone falls back to its best normal mode automatically. Changing role, camera or frame rate is blocked while armed.

**MANUAL** sends a timestamp immediately for setup and indoor testing; tap it on the three phones in crossing order.

On other Android devices, including the paired Xiaomi pad, the same APK runs in `DISPLAY` mode. It requests no camera or location permissions and never participates in clock synchronization. The pad shows each phone's live state (armed, delivered fps, GNSS uncertainty), a sensitivity slider per phone, **ARM ALL / DISARM ALL**, **PREVIEW ON/OFF**, the latest result and a persistent history of the last 50 runs.

The screen stays on while the app is open. Leaving the app disarms the phone and releases camera and GNSS; they restart when it returns, and a lost camera is reopened automatically.

All four devices must run the same build: the network protocol is versioned and older builds are ignored.

## Timing pipeline

- **Frames.** Normal mode reads the YUV Y plane and rotates/mirrors it into screen orientation (matching the preview) at ~240 px wide. High-speed mode renders the camera texture through the SurfaceTexture transform into a 180 px wide luma frame, packed four pixels per RGBA texel for readback.
- **Frame time.** Sensor timestamp (shifted from CLOCK_MONOTONIC to elapsed realtime when the source is `UNKNOWN`, with +1 ms uncertainty) plus half the exposure, plus the rolling-shutter delay of the observed column: a vertical line in portrait is a single sensor row, so the readout skew is applied per column rather than ignored.
- **Exposure.** While armed, AE/AWB are locked and, on cameras with manual sensor control, exposure is capped at 2 ms with ISO raised to keep brightness, to limit motion blur. Electronic stabilisation is disabled.
- **Detector.** Adaptive background subtraction inside the strip, with global brightness-shift removal and a noise-scaled threshold. Per-column occupancy gives the silhouette; the edge facing the line is tracked with sub-column precision, and the crossing time is interpolated between the two frames that bracket the line, using each observation's own readout time. The body must remain across the line for two more frames. Either running direction works. Uncertainty combines interpolation, frame gaps and one column of edge ambiguity.
- **Clocks.** GNSS: weighted fit over a 60 s window with outlier rejection, discontinuity reset, STALE after 5 s without samples. Wi-Fi: 4 Hz exchanges per peer, low-delay sample selection and a drift fit; uncertainty is half the minimum round trip plus fit residual.
- **Network.** Every timing event carries a per-sender sequence number and is delivered exactly once, converted with the clock offset at the event instant.

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
