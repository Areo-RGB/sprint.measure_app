package com.sprinttiming.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A capture configuration: normal sessions use a YUV ImageReader, high-speed ones the GPU path. */
data class CaptureMode(val fps: Int, val highSpeed: Boolean, val width: Int, val height: Int) {
    val label get() = if (highSpeed) "$fps HS" else "$fps"
    val key get() = if (highSpeed) "${fps}hs" else "$fps"
}

/**
 * Owns one Camera2 session for timing. Frames are converted to an upright luma grid (CPU for YUV,
 * GPU for constrained high-speed) and fed to [LineCrossingDetector] with a per-frame timing model:
 * sensor timestamp (shifted to elapsed realtime when the source is UNKNOWN) + half the exposure,
 * plus the rolling-shutter readout delay of the column being observed.
 * While armed, AE/AWB are locked, focus is fixed where possible and, with MANUAL_SENSOR, exposure is
 * capped at [MAX_EXPOSURE_NANOS] (ISO raised to compensate) to limit motion blur.
 */
class CameraTimingController(
    context: Context,
    private val preview: TextureView,
    private val cameraId: String,
    val mode: CaptureMode,
    private val onStatus: (String) -> Unit,
    private val onEvent: (LineCrossingDetector.Event) -> Unit,
    private val onLost: (CameraTimingController) -> Unit
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val chars = manager.getCameraCharacteristics(cameraId)
    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var pipeline: GlFramePipeline? = null
    private val analysisHandler get() = pipeline?.handler ?: cameraHandler
    private val detector = LineCrossingDetector()
    private val frame = LumaFrame()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var targets: List<Surface> = emptyList()
    private var openPending = false
    @Volatile private var closed = false
    @Volatile private var armed = false
    @Volatile private var lockRequested = false
    @Volatile private var requestLocked = false

    val facingName = if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
    private val sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    private val mirror = facingName == "FRONT"
    private val readout = SensorGeometry.readoutCoefficients(sensorRotation, mirror)
    private val realtimeTimestamps = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    val timestampSource = if (realtimeTimestamps) "REALTIME" else "UNKNOWN→REALTIME"
    val uprightAspect = SensorGeometry.uprightAspect(mode.width, mode.height, sensorRotation)
    private val fpsRange: Range<Int> = if (mode.highSpeed) Range(mode.fps, mode.fps) else
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().filter { it.upper == mode.fps }.maxByOrNull { it.lower } ?: Range(mode.fps, mode.fps)
    private val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
    private val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    private val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
    private val canLockFocus = (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes
    private val uprightWidth = if (sensorRotation % 180 == 90) mode.height else mode.width
    private val cpuStep = max(1, uprightWidth / ANALYSIS_WIDTH_CPU)

    // Latest capture metadata: written on the camera thread, read on the analysis thread.
    @Volatile private var exposureNanos = 0L
    @Volatile private var skewNanos = 0L
    @Volatile private var aeExposure = 0L
    @Volatile private var aeIso = 0
    @Volatile private var focusDistance: Float? = null

    // Frame statistics: written on the frame thread only.
    private var lastFrameTimestamp = 0L
    private val intervals = LongArray(240)
    private var intervalCount = 0
    private var intervalIndex = 0
    private var medianInterval = 0L
    private var lastReport = 0L
    @Volatile var deliveredFps = 0.0; private set
    @Volatile var droppedFrames = 0; private set
    @Volatile var ready = false; private set
    @Volatile var configurationRejected = false; private set

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val texture = preview.surfaceTexture ?: throw IllegalStateException("preview surface unavailable")
            if (mode.highSpeed) {
                val analysisHeight = (ANALYSIS_WIDTH_GPU / uprightAspect).roundToInt()
                texture.setDefaultBufferSize(PREVIEW_WIDTH_GPU, (PREVIEW_WIDTH_GPU / uprightAspect).roundToInt())
                val gl = GlFramePipeline(texture, mode.width, mode.height, ANALYSIS_WIDTH_GPU, analysisHeight, ::onGpuFrame, ::analyze)
                gl.start()?.let { gl.release(); throw IllegalStateException("GPU pipeline: $it") }
                pipeline = gl
                targets = listOf(gl.inputSurface!!)
            } else {
                texture.setDefaultBufferSize(mode.width, mode.height)
                val surface = Surface(texture).also { previewSurface = it }
                val imageReader = ImageReader.newInstance(mode.width, mode.height, ImageFormat.YUV_420_888, 3).also { reader = it }
                imageReader.setOnImageAvailableListener(::onImage, cameraHandler)
                targets = listOf(surface, imageReader.surface)
            }
            openPending = true
            manager.openCamera(cameraId, stateCallback, cameraHandler)
            onStatus("$facingName · ${mode.label} fps · ${mode.width}×${mode.height} · opening…")
        } catch (e: Exception) {
            openPending = false
            if (mode.highSpeed) configurationRejected = true
            onStatus("Camera unavailable (${e.message ?: e.javaClass.simpleName})")
            cameraHandler.post { if (!closed) onLost(this) }
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            openPending = false
            if (closed) { device.close(); cameraThread.quitSafely(); return }
            camera = device
            createSession(device)
        }
        override fun onDisconnected(device: CameraDevice) { openPending = false; device.close(); if (closed) cameraThread.quitSafely() else lost("Camera disconnected") }
        override fun onError(device: CameraDevice, error: Int) { openPending = false; device.close(); if (closed) cameraThread.quitSafely() else lost("Camera error $error") }
    }

    private fun lost(message: String) { ready = false; onStatus("$message · reopening"); onLost(this) }

    private fun createSession(device: CameraDevice) {
        val type = if (mode.highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        val executor = Executor { cameraHandler.post(it) }
        val config = SessionConfiguration(type, targets.map { OutputConfiguration(it) }, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) { s.close(); return }
                session = s
                applyRequest()
                ready = true
                onStatus("Ready · ${summary()}")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (closed) return
                configurationRejected = true
                lost("${mode.label} fps session rejected")
            }
        })
        try { device.createCaptureSession(config) } catch (e: Exception) { configurationRejected = true; lost("Session failed (${e.javaClass.simpleName})") }
    }

    /** Builds and submits the repeating request; locks exposure, white balance and focus while armed. */
    private fun applyRequest() {
        val s = session ?: return
        val device = camera ?: return
        val lock = lockRequested
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            targets.forEach(builder::addTarget)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            if (!mode.highSpeed) {
                // Electronic stabilisation warps and crops frames, which moves the timing line.
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            if (lock) {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (!mode.highSpeed) {
                    applyShortExposure(builder)
                    val distance = focusDistance
                    if (canLockFocus && distance != null) {
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    }
                }
            }
            requestLocked = lock
            if (mode.highSpeed) {
                val highSpeed = s as CameraConstrainedHighSpeedCaptureSession
                highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(builder.build()), captureCallback, cameraHandler)
            } else s.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
        } catch (e: Exception) {
            if (!closed) lost("Capture request failed (${e.javaClass.simpleName})")
        }
    }

    /** Shutter priority: keep the AE brightness (exposure × ISO) but cap the exposure time. */
    private fun applyShortExposure(builder: CaptureRequest.Builder) {
        val iso = isoRange ?: return
        val exposure = aeExposure; val sensitivity = aeIso
        if (!manualSensor || exposure <= MAX_EXPOSURE_NANOS || sensitivity <= 0) return
        val product = exposure.toDouble() * sensitivity
        val minExposure = exposureRange?.lower ?: 0L
        val shortExposure = max(MAX_EXPOSURE_NANOS.toDouble(), product / iso.upper).toLong().coerceIn(minExposure, exposure)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shortExposure)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, (product / shortExposure).roundToInt().coerceIn(iso.lower, iso.upper))
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / mode.fps)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureNanos = it }
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { skewNanos = it }
            if (!requestLocked) {
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { aeExposure = it }
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { aeIso = it }
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { focusDistance = it }
            }
        }
    }

    private fun onImage(source: ImageReader) {
        if (closed) return
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val timestamp = image.timestamp
            recordFrame(timestamp)
            if (!armed) return
            val plane = image.planes[0]
            frame.fill(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height, sensorRotation, mirror, cpuStep)
            analyze(frame.data, frame.width, frame.height, timestamp)
        } finally { image.close() }
    }

    private fun onGpuFrame(timestamp: Long): Boolean { recordFrame(timestamp); return armed && !closed }

    private fun analyze(data: ByteArray, width: Int, height: Int, sensorTimestamp: Long) {
        val base = toElapsedRealtime(sensorTimestamp) + exposureNanos / 2
        val event = detector.process(data, width, height, base, skewNanos, readout.first, readout.second) ?: return
        onEvent(if (realtimeTimestamps) event else event.copy(uncertaintyNanos = ThreePointTiming.rss(event.uncertaintyNanos, UNKNOWN_SOURCE_UNCERTAINTY)))
    }

    /**
     * Camera timestamps with an UNKNOWN source are CLOCK_MONOTONIC (the SystemClock.uptimeMillis base),
     * which stops during deep sleep. GNSS mapping and Wi-Fi sync use elapsedRealtimeNanos, so shift them.
     */
    private fun toElapsedRealtime(timestamp: Long): Long {
        if (realtimeTimestamps) return timestamp
        val before = System.nanoTime()
        val realtime = SystemClock.elapsedRealtimeNanos()
        val after = System.nanoTime()
        return timestamp + realtime - (before + (after - before) / 2)
    }

    private fun recordFrame(timestamp: Long) {
        val last = lastFrameTimestamp
        lastFrameTimestamp = timestamp
        if (last == 0L) return
        val interval = timestamp - last
        if (interval <= 0L || interval > 500_000_000L) return
        if (medianInterval > 0L && interval > medianInterval * 3 / 2) droppedFrames += ((interval + medianInterval / 2) / medianInterval - 1).toInt()
        intervals[intervalIndex] = interval
        intervalIndex = (intervalIndex + 1) % intervals.size
        intervalCount = minOf(intervalCount + 1, intervals.size)
        if (intervalCount >= 10 && timestamp - lastReport >= 1_000_000_000L) {
            val sorted = intervals.copyOf(intervalCount).also { it.sort() }
            medianInterval = sorted[intervalCount / 2]
            deliveredFps = 1e9 / medianInterval
            lastReport = timestamp
        }
    }

    fun summary(): String {
        val fps = if (deliveredFps > 0) String.format(Locale.US, " (%.1f)", deliveredFps) else ""
        val exposure = exposureNanos.takeIf { it > 0 }?.let { " · 1/${(1e9 / it).roundToInt()} s" } ?: ""
        val drops = if (droppedFrames > 0) " · $droppedFrames dropped" else ""
        return "$facingName · ${mode.label} fps$fps$exposure$drops · $timestampSource"
    }

    fun setArmed(value: Boolean) {
        lockRequested = value
        analysisHandler.post { if (value) detector.reset(); armed = value }
        cameraHandler.post { applyRequest() }
    }

    fun setSensitivity(value: Int) { analysisHandler.post { detector.setSensitivity(value) } }
    fun setLine(fraction: Float) { analysisHandler.post { detector.setLine(fraction) } }
    fun setPreviewEnabled(value: Boolean) { pipeline?.previewEnabled = value }
    val gpuPreviewMissing get() = pipeline?.let { !it.hasPreview } ?: false

    /** Closes on the camera thread so no frame is mid-processing and a pending open is released. */
    fun close() {
        if (closed) return
        closed = true
        ready = false
        val done = CountDownLatch(1)
        cameraHandler.post {
            try { session?.close(); camera?.close(); reader?.close() } catch (_: Exception) { }
            session = null; camera = null; reader = null
            previewSurface?.release(); previewSurface = null
            // A still-pending open must deliver its callback here so the device gets closed.
            if (!openPending) cameraThread.quitSafely()
            done.countDown()
        }
        try { done.await(1500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        pipeline?.release(); pipeline = null
    }

    companion object {
        const val MAX_EXPOSURE_NANOS = 2_000_000L
        const val UNKNOWN_SOURCE_UNCERTAINTY = 1_000_000L
        private const val ANALYSIS_WIDTH_CPU = 240
        private const val ANALYSIS_WIDTH_GPU = 180
        private const val PREVIEW_WIDTH_GPU = 540

        fun cameraFor(manager: CameraManager, facing: Int): String? =
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
                ?: manager.cameraIdList.firstOrNull()

        /** Capture modes this camera can deliver: normal YUV sessions, then constrained high-speed ones. */
        fun modes(manager: CameraManager, cameraId: String): List<CaptureMode> {
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return listOf(CaptureMode(30, false, 640, 480))
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val result = mutableListOf<CaptureMode>()
            for (fps in listOf(30, 60, 90, 120)) {
                if (ranges.none { it.upper == fps }) continue
                val frameNanos = 1_000_000_000L / fps
                val size = yuvSizes
                    .filter { val d = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it); d == 0L || d <= frameNanos }
                    .minByOrNull { abs(it.width * it.height - 640 * 480) + if (it.width * 3 == it.height * 4) 0 else 50_000 } ?: continue
                result += CaptureMode(fps, false, size.width, size.height)
            }
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in capabilities) {
                for (fps in listOf(120, 240)) {
                    if (result.any { it.fps == fps }) continue
                    val size = map.highSpeedVideoSizes.filter { s -> map.getHighSpeedVideoFpsRangesFor(s).any { it.lower == fps && it.upper == fps } }
                        .minByOrNull { it.width * it.height } ?: continue
                    result += CaptureMode(fps, true, size.width, size.height)
                }
            }
            if (result.isEmpty()) result += CaptureMode(30, false, 640, 480)
            return result
        }
    }
}
