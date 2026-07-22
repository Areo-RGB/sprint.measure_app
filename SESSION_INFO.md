# Sprint Timing App — Session Information

Last updated: 2026-07-22  
Workspace: `C:\Users\paul\projects\sprint.measure_app`

## Current device setup

| Device | ADB serial | Default role | Camera | Target / measured rate |
|---|---|---|---|---|
| Google Pixel 7 | `31071FDH2008FK` | START | Rear | 60 fps / about 59.6 fps |
| Huawei EML-L29 | `UBV0218316007905` | SPLIT | Front | 30 fps / about 29.5 fps |
| OnePlus CPH2399 | `DMIFHU7HUG9PKVVK` | FINISH | Front | 30 fps / device-dependent, about 24.6–29.6 fps observed |
| Xiaomi 2410CRP4CG pad | `4c637b9e` | DISPLAY | Disabled | Not applicable |

All four devices use package `com.sprinttiming.app`. The current debug APK is:

`app/build/outputs/apk/debug/app-debug.apk`

## Timing behavior

Each camera phone produces one source-frame timestamp. The finish phone collects the three events and sorts them chronologically rather than trusting device role or packet-arrival order:

1. Earliest timestamp = start.
2. Second timestamp = split.
3. Third timestamp = finish.

The displayed values are:

- `split time = second timestamp − earliest timestamp`
- `finish time = third timestamp − earliest timestamp`

GNSS is used only when all three events have qualified GNSS mappings. Otherwise, the app converts timestamps through separate per-phone Wi-Fi clock models and labels the result `Wi-Fi fallback`.

## Implemented features

- Camera2 source-frame timestamps and luma line-crossing detection.
- Timestamp interpolation between frames.
- Pixel rear camera configured for 60 fps.
- Front/rear camera switch; switching disarms detection.
- Manual trigger on every camera phone.
- Wi-Fi fallback when GNSS quality is insufficient.
- Separate START, SPLIT, FINISH, and DISPLAY roles.
- Huawei Android 10 / API 29 compatibility.
- Pad controls for arming/disarming all three phones.
- Independent START, SPLIT, and FINISH sensitivity sliders.
- Pad control for hiding/restoring previews while detection continues.
- Persistent split/finish result history on the pad.
- Directed broadcast, subnet-unicast discovery, event retries, pending-event delivery, and on-demand peer clock synchronization for Huawei/EMUI and high-latency Wi-Fi compatibility.
- Duplicate result suppression.

## Validation performed

- APK built successfully with `assembleDebug`.
- Two timestamp-ordering unit tests pass with zero failures.
- APK installed successfully on all four devices.
- Huawei Camera2 front-camera session confirmed active with `REALTIME` timestamps and approximately 29.5 fps delivery.
- Synthetic unordered timestamps produced the expected 8.50 s split and 20.00 s finish.
- Physical manual sequence Pixel → Huawei → OnePlus produced one pad result:
  - split: 3.33 s
  - finish: 4.72 s
  - mode: Wi-Fi fallback
- Crash buffers were empty on all four devices after final validation.

## Important accuracy note

The final physical test reported approximately ±3.7–3.8 seconds of Wi-Fi uncertainty. This proves event delivery, chronological sorting, and display behavior, but it is not accurate enough for sprint measurement.

For useful timing accuracy:

- obtain a qualified GNSS clock model on all three camera phones; or
- use a dedicated, stable, low-latency local hotspot and wait for peer synchronization to settle before running.

Do not treat a result with multi-second uncertainty as a valid measured sprint.

## Device state at the end of testing

- The final APK is installed on Pixel, Huawei, OnePlus, and Xiaomi pad.
- Huawei was unlocked and operating as the SPLIT camera.
- Huawei was charging but had about 2% battery remaining.
- The pad retained one physical test result in its history.

## Evidence

- `artifacts/device-qa/pad-live-huawei-split-final.png`
- `artifacts/device-qa/huawei-split-camera-final.png`
- `artifacts/device-qa/pad-three-phone-split-result.png`
- `app/build/test-results/testDebugUnitTest/`

## Related documentation

- `README.md`
- `sprint_timing_app_technical.md`

