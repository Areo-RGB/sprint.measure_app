---
name: android-camera2-timing
description: Build and validate raw Android Camera2 acquisition for precise event timing. Use for camera enumeration, timestamp-source qualification, YUV ImageReader pipelines, regular or constrained high-speed sessions, frame-jitter measurement, and source-frame timestamp integrity.
metadata:
  domain: android-camera2
  triggers: Camera2, SENSOR_TIMESTAMP, Image.timestamp, high speed capture, YUV_420_888, frame timing
---

# Android Camera2 Timing

## Purpose

Create a bounded raw Camera2 pipeline whose event time comes from the sensor frame, not from callback arrival, image processing completion, or wall-clock time.

## Hard invariants

- Enumerate physical camera IDs and record characteristics before choosing a lens.
- Read CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE.
- Prefer and qualify SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME because its sensor timestamps share the SystemClock.elapsedRealtimeNanos timebase.
- Treat UNKNOWN as device-local monotonic time only. Do not map it to GNSS or another camera without a measured calibration path.
- Preserve CaptureResult.SENSOR_TIMESTAMP and Image.timestamp with every frame record.
- Close every Image promptly.
- Keep acquisition bounded. Drop stale frames rather than increasing measurement latency.
- Never claim 60 or 120 fps from requested settings alone; measure delivered timestamps.

## Workflow

1. Enumerate camera IDs, lens facing, logical/physical IDs, hardware level, timestamp source, output sizes, AE FPS ranges, constrained-high-speed capability, high-speed sizes, and supported FPS ranges.
2. Produce a qualification table before selecting a camera configuration.
3. Prefer a normal capture session when the desired range is advertised by CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES.
4. Use a constrained high-speed session only when REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO is present and the required Surface combination is supported. Build requests with createHighSpeedRequestList and submit them as repeating bursts.
5. Configure an ImageReader using YUV_420_888 at the smallest resolution that preserves the finish-line ROI. Use maxImages >= 2 when acquireLatestImage is required.
6. In the image callback, acquire the latest image, capture Image.timestamp, copy only the required Y-plane ROI into owned memory, and close the Image in a finally block.
7. Match image timestamps to capture results where metadata such as exposure time is needed. Use timestamp as the primary key and tolerate callback reordering.
8. Send the compact frame record to a bounded queue. Count overwritten or dropped frames.
9. Measure frame intervals from source timestamps after warm-up. Report mean, median, p95, p99, standard deviation, minimum, maximum, duplicates, reversals, and long gaps.
10. Record exposure time, sensitivity, stabilization state, focus mode, and AE behavior alongside each qualification run.

## Frame record contract

Each accepted frame should contain:

- cameraId and optional physicalCameraId
- imageTimestampNanos
- sensorTimestampNanos when matched
- frameNumber
- exposureTimeNanos when available
- ROI width, height, row stride, pixel stride, and copied luma bytes
- queue-drop count at acquisition
- session configuration identifier

Do not retain Image or Plane objects beyond the callback.

## Timestamp checks

- Image.timestamp must be positive and monotonic for the selected stream.
- For matched frames, Image.timestamp and SENSOR_TIMESTAMP should agree according to the device's documented stream behavior; log any stable offset rather than silently rewriting timestamps.
- When timestamp source is REALTIME, compare against elapsedRealtimeNanos only for sanity bounds, never as a replacement timestamp.
- A callback arrival timestamp may be logged for latency profiling but must not be used as event time.

## Performance rules

- No Bitmap allocation, JPEG encoding, RGB conversion, full-frame copy, logging, or detector work in the image callback.
- Use one dedicated acquisition thread/executor and a separate CPU-processing dispatcher.
- Reuse byte arrays or a small pool where safe.
- Prefer a narrow ROI and grayscale/luma operations.
- Capture Perfetto traces when callback latency, scheduler stalls, or thermal throttling changes delivered cadence.

## Failure gates

Reject or downgrade the configuration when:

- timestamp source is UNKNOWN and no validated mapping exists;
- delivered FPS is below the configured minimum;
- timestamp reversals or duplicates occur;
- long-gap rate exceeds the device profile threshold;
- exposure time is too long for required motion precision;
- thermal throttling causes sustained cadence degradation;
- processing queues grow instead of dropping stale frames.

## Verification

- Unit-test timestamp matching, queue overwrite behavior, frame-interval statistics, and configuration selection.
- Run physical-device qualification on every candidate camera and mode.
- Test start/stop/re-arm cycles, permission denial, camera disconnect, session failure, background/foreground transitions, and rapid role changes.
- Save machine-readable CSV/JSON evidence and a concise Markdown report.

## Output contract

Return:

- selected camera ID, session type, size, format, and requested FPS range;
- timestamp source and whether cross-subsystem mapping is allowed;
- delivered cadence statistics and timestamp-integrity findings;
- queue/drop counts and callback-latency diagnostics;
- exposure and blur suitability;
- pass, degraded, or rejected decision with exact reasons;
- physical-device evidence paths and skipped checks.

## Official references

- https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- https://developer.android.com/reference/android/hardware/camera2/CaptureResult
- https://developer.android.com/reference/android/hardware/camera2/CameraConstrainedHighSpeedCaptureSession
- https://developer.android.com/reference/android/hardware/camera2/CameraDevice
- https://developer.android.com/reference/android/media/ImageReader
