---
name: android-two-way-clock-sync
description: Implement low-latency two-way UDP clock synchronization between Android phones as a GNSS cross-check or explicit fallback. Use for four-timestamp offset estimation, delay filtering, drift tracking, packet diagnostics, and confidence scoring.
metadata:
  domain: android-network-timing
  triggers: UDP clock sync, four timestamps, offset delay, hotspot timing, packet jitter
---

# Android Two-Way Clock Sync

## Purpose

Estimate the relationship between two phones' monotonic elapsed-realtime clocks using repeated two-way UDP exchanges. Use it primarily to cross-check independent GNSS models and secondarily as an explicit fallback when product rules permit.

## Protocol record

For sequence number s, collect:

- t1: client send time on client elapsed-realtime clock
- t2: server receive time on server elapsed-realtime clock
- t3: server send time on server elapsed-realtime clock
- t4: client receive time on client elapsed-realtime clock

Calculate, under the symmetric-path approximation:

    offset = ((t2 - t1) + (t3 - t4)) / 2
    delay  = (t4 - t1) - (t3 - t2)

Document the sign convention: offset maps client clock to server clock or vice versa. Never leave it implicit.

## Packet design

Use a compact versioned binary or deterministic serialization format containing:

- protocol version and session ID
- sequence number
- role/device ID
- t1, t2, t3, t4 as signed 64-bit nanoseconds where applicable
- flags and optional checksum/authentication field

Never transmit wall-clock time as the synchronization basis. Reject packets from another session, old sequence ranges, invalid lengths, impossible timestamps, or an unexpected peer.

## Workflow

1. Bind sockets before arming and warm the path with discarded exchanges.
2. Timestamp immediately around send/receive calls using elapsedRealtimeNanos.
3. Keep socket callbacks lightweight and pass completed samples to a bounded analysis buffer.
4. Collect samples continuously at a moderate cadence. Late samples are not retransmitted for measurement; a newer exchange is more valuable.
5. Reject negative or impossible delays and stale/reordered duplicates.
6. Select low-delay samples within a sliding window because they are less affected by queueing asymmetry.
7. Apply robust filtering to offset values, for example a minimum-delay subset followed by median/MAD rejection.
8. Fit offset drift over time. Publish offset at a declared reference epoch, rate, uncertainty, age, delay statistics, and accepted-sample ratio.
9. Compare the Wi-Fi relationship with the difference between both phones' GNSS clock models.
10. Degrade confidence when hotspot state, route, power mode, thermal state, packet loss, or delay distribution changes.

## GNSS cross-check

When both devices have LOCKED GNSS models:

- convert the same conceptual instant into each elapsed-realtime domain;
- derive the expected inter-device offset from the two GNSS models;
- compare it with the filtered UDP offset at the same reference epoch;
- track disagreement and uncertainty over time.

Do not silently blend conflicting models. Record which source is authoritative, which is diagnostic, and why.

## Fallback policy

Fallback must be explicit and observable. Define:

- conditions that permit Wi-Fi-only timing;
- minimum warm-up duration and sample count;
- maximum offset uncertainty and model age;
- maximum delay percentile and loss rate;
- behavior when GNSS returns and disagrees;
- UI/log indication that the timing basis changed.

Prefer rejecting a run over reporting an unexplained source switch.

## Networking constraints

- Support hotspot/client address discovery without assuming a fixed subnet forever.
- Bind to the intended network when multiple transports are active.
- Handle socket closure, role changes, screen/background restrictions, and Wi-Fi reconnection.
- Keep message authentication lightweight but prevent accidental cross-session contamination.
- Do not build TCP-style reliability on top of measurement packets; missing samples are expected.

## Testing

- Unit-test equations and sign conventions with known timestamps.
- Simulate constant offset, linear drift, asymmetric delay, burst jitter, packet loss, duplication, reordering, and stale sessions.
- Verify bounded buffers and cancellation cleanup.
- On physical phones, measure idle, loaded, hotspot, thermal, and distance scenarios and compare against GNSS-derived offsets.

## Output contract

Return:

- session ID and role mapping;
- reference epoch, offset, and rate;
- uncertainty and confidence state;
- delay min/median/p95/p99;
- sent, received, lost, duplicate, reordered, accepted, and rejected counts;
- GNSS disagreement when available;
- exact reason for fallback or rejection.
