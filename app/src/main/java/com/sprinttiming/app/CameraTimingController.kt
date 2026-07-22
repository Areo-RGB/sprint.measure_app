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
import android.view.Surface
import android.view.TextureView

class CameraTimingController(private val context: Context, private val preview: TextureView, private val lensFacing: Int, private val onStatus: (String) -> Unit, private val onEvent: (LineCrossingDetector.Event) -> Unit) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera-acquisition").apply { start() }
    private val handler = Handler(thread.looper)
    private val detector = LineCrossingDetector()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var armed = false
    var deliveredFrames = 0L
    var droppedFrames = 0L
    var timestampSource = "unknown"
    var facingName = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
        private set

    @SuppressLint("MissingPermission") fun start() {
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lensFacing }
            ?: manager.cameraIdList.firstOrNull()
            ?: return
        val chars = manager.getCameraCharacteristics(id)
        val actualFacing = chars.get(CameraCharacteristics.LENS_FACING)
        facingName = if (actualFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
        timestampSource = if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN"
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val size = sizes.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) } ?: Size(640, 480)
        reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { r ->
            r.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    deliveredFrames++
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
        onStatus("$facingName camera $id · ${size.width}×${size.height} · timestamp $timestampSource")
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
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(30, 30))
                }.build(); s.setRepeatingRequest(request, null, handler); onStatus("Ready · $facingName · Camera2 30 fps · $timestampSource")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = onStatus("Camera configuration failed")
        }, handler)
    }

    fun setArmed(value: Boolean) { armed = value; if (value) detector.reset() }
    fun close() { session?.close(); camera?.close(); reader?.close(); thread.quitSafely() }
}
