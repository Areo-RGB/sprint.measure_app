# Profiling Playbook

## Startup

Measure:

- Time to first frame
- Time to full display
- Main-thread blocking during app launch

## UI smoothness

Measure:

- Frame time percentile distribution
- Jank burst windows
- Composable recomposition frequency (where relevant)

## Memory and ANR

Measure:

- Heap growth under steady state
- Allocation spikes during key flows
- ANR traces and blocked thread patterns

## Battery

Measure:

- Background task frequency
- Network polling behavior
- Wake lock misuse

## Validation protocol

- Capture baseline with reproducible scenario
- Apply one optimization batch
- Re-measure with same scenario
- Keep change if improvement is stable and meaningful
