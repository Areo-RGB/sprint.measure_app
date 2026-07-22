package com.sprinttiming.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.Range
import android.view.Surface
import android.view.TextureView

class CameraTimingController(private val context: Context, private val preview: TextureView, private val lensFacing: Int, private val desiredFps: Int, private val onStatus: (String) -> Unit, private val onEvent: (LineCrossingDetector.Event) -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera-acquisition").apply { start() }
    private val handler = Handler(thread.looper)
    private val detector = LineCrossingDetector()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var armed = false
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
        timestampSource = if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN"
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
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    deliveredFrames++
                    recordFrameInterval(image.timestamp)
                    if (!armed) return@setOnImageAvailableListener
                    val plane = image.planes[0]; val w = image.width; val h = image.height
                    val out = ByteArray(w * h); val buffer = plane.buffer; val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
                    for (row in 0 until h) { val base = row * rowStride; for (col in 0 until w) out[row * w + col] = buffer.get(base + col * pixelStride) }
                    detector.process(out, w, h, image.timestamp)?.let(onEvent)
                } finally { image.close() }
            }, handler)
        }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) { camera = device; createSession(size) }
            override fun onDisconnected(device: CameraDevice) { device.close(); onStatus("Camera disconnected") }
            override fun onError(device: CameraDevice, error: Int) { device.close(); onStatus("Camera error $error") }
        }, handler)
        onStatus("$facingName camera $id · ${size.width}×${size.height} · target ${fpsRange.lower}-${fpsRange.upper} fps · $timestampSource")
    }

    private fun createSession(size: Size) {
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture); val analysis = reader?.surface ?: return
        camera?.createCaptureSession(listOf(surface, analysis), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s; val request = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface); addTarget(analysis)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }.build(); s.setRepeatingRequest(request, null, handler); onStatus("Ready · $facingName · target ${fpsRange.lower}-${fpsRange.upper} fps · $timestampSource")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = onStatus("Camera configuration failed")
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
    fun close() { session?.close(); camera?.close(); reader?.close(); thread.quitSafely() }
}
