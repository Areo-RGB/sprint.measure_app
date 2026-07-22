---
name: android-gnss-clock-model
description: Build an Android GNSS-to-elapsed-realtime clock model with uncertainty, drift, discontinuity handling, confidence gates, and synthetic tests. Use when converting Camera2 REALTIME timestamps into a shared GPS timebase.
metadata:
  domain: android-gnss-timing
  triggers: GnssClock, FullBiasNanos, BiasNanos, elapsedRealtimeNanos, clock drift, GPS time
---

# Android GNSS Clock Model

## Purpose

Estimate a continuously validated affine mapping from Android elapsed realtime to GPS time:

    gpsTimeNanos = rate * elapsedRealtimeNanos + offset

Use the model to convert qualified Camera2 REALTIME timestamps into a shared cross-device timebase while exposing uncertainty and rejecting invalid runs.

## Required inputs

For every GnssClock sample, capture presence flags and values for:

- timeNanos
- fullBiasNanos
- biasNanos and biasUncertaintyNanos
- driftNanosPerSecond and driftUncertaintyNanosPerSecond
- elapsedRealtimeNanos and elapsedRealtimeUncertaintyNanos
- timeUncertaintyNanos
- hardwareClockDiscontinuityCount

Do not assume optional fields exist. Record API level and device/build fingerprint.

## Sample construction

When full bias is available:

    gpsTimeNanos = timeNanos - (fullBiasNanos + biasNanosOrZero)

Pair that GPS estimate with GnssClock.elapsedRealtimeNanos when available. Do not pair it with callback arrival time unless the device lacks the field and a separately validated fallback is explicitly enabled.

## Model workflow

1. Reject samples missing fullBiasNanos or elapsedRealtimeNanos for the primary model.
2. Reset the sample window whenever hardwareClockDiscontinuityCount changes.
3. Normalize x values around a recent origin before regression to reduce floating-point loss.
4. Maintain a rolling time-bounded window rather than an unbounded history.
5. Fit offset and rate using weighted least squares or a robust regression. Weight samples using reported timing, bias, and elapsed-realtime uncertainties when available.
6. Perform residual outlier rejection with a robust statistic such as median absolute deviation. Never reject solely because a sample disagrees with the current model; retain diagnostics.
7. Refit after rejection and calculate residual RMS, max residual, sample span, sample count, and rate uncertainty.
8. Compare fitted rate with reported drift when available. Treat disagreement as a health signal, not automatic proof that either source is wrong.
9. Publish immutable model snapshots with generation IDs.
10. Convert camera timestamps only with the model generation active at event time, or with a clearly defined retrospective fit that cannot cross a discontinuity.

## Uncertainty budget

Return both converted time and uncertainty. Include at minimum:

- GNSS time/bias uncertainty;
- elapsed-realtime alignment uncertainty;
- regression residual uncertainty;
- rate uncertainty projected across distance from the model origin;
- model age and extrapolation penalty;
- camera timestamp and detector interpolation uncertainty supplied by downstream components.

Do not report false precision. Keep nanoseconds internally, but present milliseconds or microseconds according to the final uncertainty.

## Confidence states

Use explicit states such as:

- ACQUIRING: insufficient accepted samples or span
- LOCKED: all quality gates pass
- DEGRADED: model is usable but one or more warning gates fail
- DISCONTINUITY: model reset required
- STALE: no recent accepted samples
- INVALID: required fields absent or uncertainty exceeds limit

A timing run may arm only when the configured state and uncertainty gate pass.

## Failure gates

At minimum configure thresholds for:

- minimum accepted sample count;
- minimum observation span;
- maximum model age;
- maximum residual RMS and max residual;
- maximum projected conversion uncertainty;
- allowed rate range;
- discontinuity reset behavior;
- minimum accepted-sample ratio.

Thresholds belong in a device profile and must be justified by physical testing.

## Testing

Create deterministic tests with synthetic samples for:

- exact offset and rate recovery;
- positive and negative drift;
- Gaussian noise and reported uncertainty weights;
- large outliers;
- missing optional fields;
- hardware clock discontinuity;
- stale model behavior;
- extrapolation uncertainty growth;
- floating-point stability at nanosecond-scale epoch values.

Also compare model snapshots against recorded physical-device GNSS logs.

## Output contract

Return:

- model generation and state;
- rate, offset, and origins;
- accepted/rejected sample counts;
- window span and model age;
- residual statistics;
- conversion uncertainty;
- discontinuity count;
- reasons for every degraded or invalid state.

## Official reference

- https://developer.android.com/reference/android/location/GnssClock
