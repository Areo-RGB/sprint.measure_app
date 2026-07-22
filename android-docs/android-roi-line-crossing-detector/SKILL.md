---
name: android-roi-line-crossing-detector
description: Implement a deterministic finish-line crossing detector from Camera2 Y-plane ROIs using directional zone progression, temporal confirmation, interpolation, bounded processing, and confidence metrics.
metadata:
  domain: computer-vision-timing
  triggers: finish line, ROI detector, Y plane, frame difference, line crossing, interpolation
---

# Android ROI Line-Crossing Detector

## Purpose

Detect the instant an athlete crosses a calibrated finish line using a narrow luma ROI and source-frame timestamps. Version one is deterministic classical vision, not a full-frame ML model.

## Inputs

- copied Y-plane ROI bytes with width, height, row stride, and pixel stride normalized by the acquisition layer;
- sourceFrameTimestampNanos;
- ROI geometry and line orientation;
- travel direction;
- detector configuration and device profile;
- camera exposure metadata when available.

## Geometry

Represent the finish region as three ordered zones:

1. pre-line zone;
2. line zone;
3. post-line zone.

Support horizontal or vertical travel by transforming the calibrated ROI into a canonical axis. Keep coordinates normalized so configuration can survive resolution changes, then validate the actual pixel dimensions at runtime.

## Baseline algorithm

1. Apply a small temporal luma smoother or background estimate.
2. Compute absolute difference against the previous smoothed frame or adaptive background.
3. Threshold changed pixels using a fixed device-calibrated threshold or a bounded adaptive threshold.
4. Optionally apply tiny morphology or connected-component filtering only when measured noise requires it.
5. Aggregate changed-pixel occupancy and intensity per zone.
6. Maintain a short state machine requiring directional progression: pre-line activation, line activation, then post-line activation.
7. Require confirmation across adjacent frames and reject single-frame flashes.
8. Detect the crossing between the last clearly pre-line state and the first clearly post-line/line-crossed state.
9. Interpolate event time between source timestamps using a monotonic crossing score when the score brackets the threshold.
10. Emit one event per armed passage and apply a refractory/re-arm rule.

## State machine

Use explicit states such as:

- CLEAR
- APPROACHING
- ON_LINE
- CROSSED_PENDING_CONFIRMATION
- CONFIRMED
- REJECTED

Every transition must record timestamp, zone metrics, threshold, and reason. Avoid a collection of loosely related booleans.

## Interpolation

For bracketing frames (t0, score0) and (t1, score1), with threshold h:

    alpha = clamp((h - score0) / (score1 - score0), 0, 1)
    crossingTime = t0 + alpha * (t1 - t0)

Use interpolation only when the score is well-conditioned and progression is valid. Otherwise choose the conservative source-frame bound and increase uncertainty. Never interpolate using processing completion times.

## Confidence and uncertainty

Include:

- frame interval around the event;
- interpolation fraction and score slope;
- zone progression consistency;
- changed-pixel area and signal-to-background ratio;
- number of confirming frames;
- exposure/motion-blur warning;
- dropped-frame count near the event;
- ROI obstruction or saturation warning.

Detector confidence must feed the final run acceptance gate; it is not cosmetic UI metadata.

## Performance rules

- Operate on the narrow luma ROI only.
- Reuse primitive arrays and avoid per-frame object graphs.
- Keep work single-pass where possible.
- Use a bounded input buffer and process newest useful frames.
- Record processing latency separately from event time.
- Add OpenCV only after a measured need for morphology, optical flow, or calibration utilities.

## Failure and rejection cases

Reject or flag:

- reverse-direction progression;
- isolated shadows/flicker without ordered zone movement;
- camera movement or ROI calibration loss;
- multiple subjects when the detector cannot disambiguate;
- insufficient confirming frames;
- frame gap spanning the event beyond the allowed uncertainty;
- saturation, darkness, severe blur, or obstruction;
- repeated trigger during refractory period.

## Testing

Build a fixture format containing ROI frames, source timestamps, expected crossing interval, and metadata. Test:

- clean crossings at different speeds;
- no crossing;
- reverse motion;
- shadows and lighting flicker;
- partial occlusion;
- dropped frames and irregular cadence;
- two athletes close together;
- camera shake;
- interpolation edge cases;
- deterministic repeatability.

Validate final thresholds on recorded Pixel 7 and OnePlus Nord 2T clips rather than emulator camera output.

## Output contract

Return:

- event timestamp and lower/upper time bounds;
- detector confidence and acceptance state;
- bracketing frame timestamps;
- zone metrics and state transitions;
- dropped-frame and blur warnings;
- configuration/version identifier;
- retained diagnostic snippet policy without storing full private video by default.
