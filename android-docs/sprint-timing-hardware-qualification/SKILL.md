---
name: sprint-timing-hardware-qualification
description: Qualify specific Android phones and physical cameras for sprint timing using repeatable Camera2, GNSS, UDP, detector, thermal, and end-to-end uncertainty evidence. Use before enabling a device configuration in production.
metadata:
  domain: hardware-validation
  triggers: Pixel 7, OnePlus Nord 2T, device qualification, camera fps test, GNSS timing test
---

# Sprint-Timing Hardware Qualification

## Purpose

Turn device assumptions into evidence. A model or camera mode is supported only after repeatable physical-device qualification and a versioned device profile.

## Target devices

Initial target matrix:

- Google Pixel 7
- OnePlus Nord 2T

Do not assume all units, OS builds, camera IDs, or vendor updates behave identically. Record build fingerprint, security patch, app version, battery state, and ambient conditions.

## Qualification phases

### 1. Inventory

For every physical camera:

- camera ID, lens facing, logical/physical relationship;
- hardware support level;
- timestamp source;
- output sizes and YUV combinations;
- normal AE FPS ranges;
- constrained-high-speed capability, sizes, and FPS ranges;
- stabilization, focus, exposure, and sensor metadata availability.

### 2. Camera cadence

For each candidate configuration:

- warm up;
- capture a fixed-duration source-timestamp log;
- calculate delivered FPS and interval statistics;
- detect duplicates, reversals, long gaps, callback lag, and queue drops;
- repeat cold, warm, and thermally stressed runs;
- record exposure time distribution and motion-blur suitability.

### 3. GNSS clock

Record continuous GnssClock samples outdoors with good sky view:

- optional-field availability;
- discontinuity changes;
- model lock time;
- rate/offset stability;
- residual and projected uncertainty;
- behavior through screen state, temporary signal degradation, and reacquisition.

### 4. Wi-Fi cross-check

Run both role directions where feasible:

- hotspot and client setup time;
- packet loss and delay distribution;
- offset stability and drift;
- agreement with the two GNSS models;
- behavior under CPU load, network traffic, screen transitions, and thermal load.

### 5. Detector fixtures

Capture representative finish-line clips and annotate event intervals using an independent method. Evaluate:

- false positives and false negatives;
- event error distribution;
- confidence calibration;
- sensitivity to lighting, clothing, shadows, camera angle, and subject speed;
- dropped-frame and exposure effects.

### 6. End-to-end runs

Execute paired-phone runs from arm through result:

- role assignment and connectivity;
- camera and GNSS readiness;
- synchronization agreement;
- crossing detection;
- time conversion;
- result exchange and persistence;
- disarm/re-arm and failure recovery.

## Evidence format

Store machine-readable JSON/CSV plus a Markdown summary. Include:

- device/build/app identifiers;
- exact camera/session configuration;
- test conditions and duration;
- raw statistic summaries and artifact hashes;
- pass, at-risk, or fail for each gate;
- known limitations and operator instructions;
- profile version and approver/date.

Do not commit private full-resolution video by default. Keep compact, consented fixtures or derived ROI snippets under an explicit retention policy.

## Suggested gates

Define values from the product uncertainty budget, then enforce them consistently:

- timestamp source must be REALTIME for the primary architecture;
- no timestamp reversals or duplicates;
- delivered FPS and long-gap rate meet profile limits;
- GNSS model reaches LOCKED within allowed time and remains below uncertainty threshold;
- UDP/GNSS disagreement remains within combined uncertainty;
- detector error and false-trigger rates pass the scenario set;
- thermal run does not cause sustained unacceptable degradation;
- end-to-end result uncertainty and failure behavior pass.

Do not invent universal thresholds in the skill. The qualification report must state the chosen values and rationale.

## Automation

Provide repeatable Gradle/ADB commands or an instrumented qualification screen that can:

- enumerate camera capabilities;
- export frame timestamp logs;
- export GNSS clock logs;
- run UDP exchanges;
- capture Perfetto traces;
- run detector fixtures;
- package artifacts with a manifest.

UI automation can validate navigation and states, but camera/GNSS timing gates require physical hardware.

## Release rule

A device profile is invalidated or must be rechecked after:

- major Android/vendor update;
- camera HAL behavior change;
- timing-model or detector algorithm change;
- session configuration change;
- unexplained field discrepancy;
- new device unit showing materially different behavior.

## Output contract

Return:

- qualification matrix;
- selected supported camera configuration per device;
- all gate results with evidence paths;
- uncertainty summary;
- unsupported modes;
- required operator setup;
- retest triggers;
- final supported, conditional, or unsupported decision.
