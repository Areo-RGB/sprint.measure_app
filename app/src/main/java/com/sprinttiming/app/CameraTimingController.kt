package com.sprinttiming.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.util.Range
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraTimingController(private val context: Context, private val preview: TextureView, private val lensFacing: Int, private val desiredFps: Int, private val onStatus: (String) -> Unit, private val onEvent: (LineCrossingDetector.Event) -> Unit, private val onLost: (CameraTimingController) -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera-acquisition").apply { start() }
    private val handler = Handler(thread.looper)
    private val detector = LineCrossingDetector()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var armed = false
    @Volatile private var closed = false
    private var openPending = false
    private var sensorRotation = 0
    private var mirrorFrames = false
    private var realtimeTimestamps = true
    private var fpsRange = Range(30, 30)
    private var lastFrameTimestamp = 0L
    private val frameIntervals = LongArray(120)
    private var intervalCount = 0
    private var intervalIndex = 0
    private var lastFpsReport = 0L
    var deliveredFrames = 0L
    var droppedFrames = 0L
    var timestampSource = "unknown"
    var facingName = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
        private set
    @Volatile var deliveredFps = 0.0
        private set

    @SuppressLint("MissingPermission") fun start() {
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensFacing }
            ?: manager.cameraIdList.firstOrNull()
            ?: return
        val chars = manager.getCameraCharacteristics(id)
        val actualFacing = chars.get(CameraCharacteristics.LENS_FACING)
        facingName = if (actualFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
        realtimeTimestamps = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        timestampSource = if (realtimeTimestamps) "REALTIME" else "UNKNOWN→REALTIME"
        sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        mirrorFrames = actualFacing == CameraCharacteristics.LENS_FACING_FRONT
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        fpsRange = ranges.filter { it.lower <= desiredFps && it.upper >= desiredFps }.minByOrNull { (it.upper - it.lower) * 1000 + kotlin.math.abs(it.upper - desiredFps) }
            ?: ranges.filter { it.lower <= 30 && it.upper >= 30 }.minByOrNull { it.upper - it.lower }
            ?: Range(30, 30)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val targetFrameDuration = 1_000_000_000L / fpsRange.upper.coerceAtLeast(1)
        val cadenceSizes = sizes.filter { val duration = map?.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) ?: 0L; duration == 0L || duration <= targetFrameDuration }
        val size = (if (cadenceSizes.isNotEmpty()) cadenceSizes else sizes.toList()).minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) } ?: Size(640, 480)
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { r ->
            r.setOnImageAvailableListener({ source ->
                if (closed) return@setOnImageAvailableListener
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    deliveredFrames++
                    recordFrameInterval(image.timestamp)
                    if (!armed) return@setOnImageAvailableListener
                    val plane = image.planes[0]
                    val frame = LumaFrame.copy(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height, sensorRotation, mirrorFrames)
                    detector.process(frame.data, frame.width, frame.height, toElapsedRealtime(image.timestamp))?.let(onEvent)
                } finally { image.close() }
            }, handler)
        }
        openPending = true
        try { manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) { openPending = false; if (closed) { device.close(); thread.quitSafely(); return }; camera = device; createSession(size) }
            override fun onDisconnected(device: CameraDevice) { openPending = false; device.close(); if (closed) thread.quitSafely() else { onStatus("Camera disconnected · reopening"); onLost(this@CameraTimingController) } }
            override fun onError(device: CameraDevice, error: Int) { openPending = false; device.close(); if (closed) thread.quitSafely() else { onStatus("Camera error $error · reopening"); onLost(this@CameraTimingController) } }
        }, handler) } catch (e: Exception) {
            openPending = false
            onStatus("Camera unavailable (${e.javaClass.simpleName}) · reopening")
            handler.post { if (!closed) onLost(this) }
            return
        }
        onStatus("$facingName camera $id · ${size.width}×${size.height} · target ${fpsRange.lower}-${fpsRange.upper} fps · $timestampSource")
    }

    private fun createSession(size: Size) {
        val texture = preview.surfaceTexture ?: run { onStatus("Preview surface unavailable · reopening"); onLost(this); return }
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture); val analysis = reader?.surface ?: return
        camera?.createCaptureSession(listOf(surface, analysis), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) { s.close(); return }
                session = s; val request = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface); addTarget(analysis)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }.build(); s.setRepeatingRequest(request, null, handler); onStatus("Ready · $facingName · target ${fpsRange.lower}-${fpsRange.upper} fps · $timestampSource")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { if (!closed) { onStatus("Camera configuration failed · reopening"); onLost(this@CameraTimingController) } }
        }, handler)
    }

    private fun recordFrameInterval(timestamp: Long) {
        if (lastFrameTimestamp > 0) {
            val interval = timestamp - lastFrameTimestamp
            if (interval in 1_000_000L..100_000_000L) {
                frameIntervals[intervalIndex] = interval; intervalIndex = (intervalIndex + 1) % frameIntervals.size
                intervalCount = (intervalCount + 1).coerceAtMost(frameIntervals.size)
                if (intervalCount >= fpsRange.upper && timestamp - lastFpsReport > 2_000_000_000L) {
                    val sorted = frameIntervals.copyOf(intervalCount).sorted()
                    deliveredFps = 1_000_000_000.0 / sorted[sorted.size / 2]
                    lastFpsReport = timestamp
                    onStatus("$facingName · target ${fpsRange.lower}-${fpsRange.upper} · delivered ${"%.1f".format(java.util.Locale.US, deliveredFps)} fps · $timestampSource")
                }
            }
        }
        lastFrameTimestamp = timestamp
    }

    fun setArmed(value: Boolean) { armed = value; if (value) detector.reset() }
    fun setSensitivity(value: Int) = detector.setSensitivity(value)
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

    /** Closes on the camera thread so no frame is mid-processing and a pending open is released. */
    fun close() {
        if (closed) return
        closed = true
        val done = CountDownLatch(1)
        handler.post {
            try { session?.close(); camera?.close(); reader?.close() } catch (_: Exception) { }
            session = null; camera = null; reader = null
            // A still-pending open must deliver its callback here so the device gets closed.
            if (!openPending) thread.quitSafely()
            done.countDown()
        }
        try { done.await(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
    }
}
