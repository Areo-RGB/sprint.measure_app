# Kotlin concurrency patterns for timing pipelines

This local reference fills a dangling upstream link and specializes the imported skill for bounded sensor pipelines.

## Ownership

- Give camera acquisition, GNSS collection, and network synchronization separate child jobs under one run-scoped parent job.
- Cancel the run scope when disarming, changing roles, closing the camera, or detecting a fatal clock discontinuity.
- Never let a repository create an ownerless scope. Inject scopes or dispatchers where deterministic testing is required.

## Bounded pipelines

- Camera callbacks must do minimal work: copy only the required ROI bytes and source timestamp, then release the Image.
- Use a bounded channel or ring buffer. When processing falls behind, discard stale frames rather than building latency.
- Keep source timestamps attached to data throughout the pipeline. Never replace them with callback or completion time.

## Dispatchers

- Camera callbacks: dedicated HandlerThread or executor required by the Camera2 setup.
- CPU image processing: Dispatchers.Default or a limited parallelism dispatcher.
- File/network I/O: Dispatchers.IO.
- UI state: Main dispatcher only.

## Cancellation and cleanup

- Close Image, ImageReader, CameraCaptureSession, CameraDevice, sockets, and callbacks in finally blocks.
- Re-throw CancellationException.
- Use NonCancellable only for a small, bounded cleanup action; never for normal processing.

## Testing

- Inject clock, dispatcher, sample source, and packet transport interfaces.
- Use runTest and virtual time for drift-window and timeout tests.
- Use Turbine for state and confidence streams.
- Assert the buffer remains bounded during deliberately slow processing.
