# Sprint Timing App — Recommended Technical Handoff

- **Platform**
  - Android only.
  - Personal-use app for three known camera phones: **Google Pixel 7**, **Huawei EML-L29**, and **OnePlus Nord 2T 5G**.
  - Optimize for these exact devices rather than broad Android compatibility.

- **Overall Architecture**
  - Place one phone at each of three timing lines. Pixel defaults to START, Huawei to SPLIT, and OnePlus to FINISH.
  - Each phone performs **local camera analysis** and detects the athlete crossing a virtual timing line.
  - Never send video between phones.
  - Exchange only run state, health/status, synchronization data, and final event timestamps.
  - Collect exactly one event timestamp from each camera phone, sort the three values, and interpret earliest as start, second as split, and third as finish.
  - Report `split time = second − earliest` and `finish time = third − earliest`; do not use packet arrival order.

- **Primary Shared Time Domain**
  - Use **GPS/GNSS time as the common global time domain**.
  - Each phone independently maintains a mapping:
    - local monotonic clock → GPS time.
  - Timestamp the camera event locally first, then convert it into GPS time.
  - Do not request “current GPS time” when a crossing happens.
  - Continuously update clock offset and drift models before and during an armed run.

- **Timing Model**
  - Use the Android monotonic time domain locally.
  - Preferred mapping:
    - `GPS time = rate × local monotonic time + offset`.
  - Track GNSS clock discontinuities and invalidate/rebuild the model when required.
  - Maintain a synchronization quality/confidence estimate.
  - Reject or flag runs when time uncertainty exceeds the configured threshold.

- **Secondary Synchronization / Cross-Check**
  - Keep a direct Wi-Fi link between all three phones.
  - Recommended first implementation:
    - one phone hotspot;
    - other phone connected locally;
    - small UDP timing exchanges.
  - Use Wi-Fi synchronization as:
    - backup when GNSS quality is insufficient;
    - independent cross-check of the GNSS-derived clock relationship.
  - Prefer repeated two-way exchanges, minimum-delay sample selection, outlier rejection, and drift estimation.
  - Maintain a separate offset/delay model per peer; a single shared Wi-Fi offset is invalid once a third timing phone is added.

- **Camera Timestamp Requirement**
  - Verify on all three physical camera devices that the selected camera reports a timestamp source compatible with the Android elapsed realtime domain.
  - Prefer cameras exposing `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`.
  - Test the exact physical camera used for timing; do not assume all cameras on the phone behave identically.
  - Treat this as a hardware qualification test before finalizing the architecture.

- **Camera API Recommendation**
  - Start with **Camera2**, not CameraX, for the measurement core.
  - Reason:
    - tighter control over capture sessions;
    - frame-rate ranges;
    - physical camera selection;
    - timestamps;
    - exposure behavior;
    - high-speed modes;
    - device-specific tuning.
  - CameraX remains suitable for rapid prototyping or UI preview, but Camera2 is the preferred final timing implementation.

- **Detection Strategy**
  - Do not use generic full-frame motion detection.
  - Use a user-positioned **virtual timing line** with a narrow analysis region around it.
  - Analyze only the minimum region needed to determine a crossing.
  - Detect directional progression across the line rather than simple frame change.

- **ROI Recommendation**
  - Use a long, narrow strip centered on the virtual timing line.
  - Divide it conceptually into:
    - pre-line zone;
    - line zone;
    - post-line zone.
  - Confirm a crossing from ordered motion progression across these zones.
  - Ignore the rest of the frame during the main detection stage.
  - Allow the user to position and resize the ROI during setup.

- **Image Data Recommendation**
  - Start with the **Y/luma plane only** from YUV camera frames.
  - Advantages:
    - lowest processing cost;
    - no YUV-to-RGB conversion;
    - sufficient for silhouette/motion change in most outdoor conditions.
  - Add chroma or RGB only if real testing shows luma cannot reliably separate the runner from the background.

- **Recommended Detection Pipeline**
  - Luma ROI extraction.
  - Small amount of temporal smoothing.
  - Frame-to-frame or short-term background difference.
  - Threshold changed pixels.
  - Aggregate change across the line strip.
  - Track directional occupancy from one side of the line to the other.
  - Confirm the event over multiple adjacent frames.
  - Estimate the crossing instant between frames when possible.

- **Preferred Detection Method**
  - First choice: **ROI temporal difference + directional line-crossing logic**.
  - Avoid ML/person detection for version one.
  - Add object tracking or ML only if simple image analysis produces unacceptable false triggers.
  - The system should detect the crossing event, not identify the athlete.

- **Resolution**
  - Do not analyze the full sensor resolution.
  - Begin around:
    - **640×480**, or
    - another low-resolution stream with similar pixel density across the timing ROI.
  - Higher resolution is useful only if the runner occupies too few pixels at the timing line.
  - Prioritize:
    - stable frame delivery;
    - high frame rate;
    - low processing load;
    - reliable timestamps.
  - Resolution should be increased only after proving that detection quality requires it.

- **Frame Rate**
  - Prefer the highest stable frame rate that preserves reliable timestamps and usable exposure.
  - Target order:
    - 120 fps if consistently supported;
    - otherwise 60 fps;
    - 30 fps only as a fallback.
  - Do not choose 240 fps automatically; reduced exposure, limited resolution, and special high-speed session constraints may outweigh the timing benefit.
  - Validate actual delivered frame intervals, not only the requested FPS setting.

- **Frame Handling**
  - Always prioritize the newest frame.
  - Never allow an analysis backlog to build.
  - If processing falls behind, drop old frames rather than introducing detection latency.
  - Event time must come from the relevant camera frame timestamp, not from the moment processing finishes.

- **Exposure and Image Stability**
  - Lock or strongly stabilize exposure and focus before arming when practical.
  - Avoid automatic exposure changes during a run because they can look like motion.
  - Prefer short enough exposure to limit motion blur.
  - Avoid digital stabilization or processing modes that may alter frame timing or geometry unless tested.

- **Crossing Timestamp**
  - The detector may decide after the event that a crossing occurred.
  - The reported timestamp must correspond to the frame or interpolated instant containing the actual line crossing.
  - Detection-processing latency must not be included in the sprint time.

- **Recommended Final System**
  - Camera2 capture on all three phones.
  - Narrow line-based ROI.
  - Luma-only analysis initially.
  - Lightweight directional temporal-difference detector.
  - 60 or 120 fps depending on measured stability.
  - Camera-frame timestamp as the event source.
  - Local monotonic-to-GPS mapping on each phone.
  - Wi-Fi synchronization as secondary validation and fallback.
  - Confidence score for every run based on:
    - GNSS clock uncertainty;
    - GNSS/Wi-Fi agreement;
    - camera timing stability;
    - detector confidence.

- **First Hardware Qualification Tests**
  - Verify the selected camera timestamp source on all three phones.
  - Verify GNSS clock fields and elapsed-realtime mapping.
  - Measure real delivered FPS and frame jitter.
  - Measure GNSS/Wi-Fi clock-model agreement over several minutes.
  - Record sample sprints and inspect false triggers, missed crossings, and estimated timing uncertainty.

- **Recommended MVP Priority**
  1. Prove stable camera timestamps.
  2. Prove GNSS-to-local-clock mapping.
  3. Prove narrow-ROI luma crossing detection.
  4. Add Wi-Fi synchronization cross-check.
  5. Add uncertainty/confidence reporting.
  6. Only then optimize frame rate, interpolation, and advanced detection.
