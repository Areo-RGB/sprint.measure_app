package com.sprinttiming.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private enum class Role { START, FINISH, DISPLAY }
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var sync: TextView
    private lateinit var result: TextView
    private lateinit var arm: Button
    private lateinit var manual: Button
    private lateinit var cameraChoice: Button
    private lateinit var roleButton: Button
    private var role = when {
        Build.MANUFACTURER.contains("OnePlus", true) -> Role.FINISH
        Build.MANUFACTURER.contains("Google", true) || Build.MODEL.contains("Pixel", true) -> Role.START
        else -> Role.DISPLAY
    }
    private val gnss = GnssClockModel()
    private var camera: CameraTimingController? = null
    private var startGps: Long? = null
    private var startUncertainty = 0L
    private var startUsesWifi = false
    private lateinit var peer: PeerTiming
    private lateinit var location: LocationManager
    private var armed = false
    private var cameraFacing = CameraCharacteristics.LENS_FACING_FRONT
    private var gnssCallback: GnssMeasurementsEvent.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        peer = PeerTiming(
            displayOnly = role == Role.DISPLAY,
            onStartEvent = { eventTime, uncertainty, wifiFallback -> runOnUiThread {
                val localStart = if (wifiFallback && role != Role.DISPLAY) peer.offsetNanos?.let { eventTime - it } else eventTime
                if (localStart == null) { result.text = "START RECEIVED\nWi-Fi clock model not ready"; return@runOnUiThread }
                startGps = localStart; startUncertainty = uncertainty; startUsesWifi = wifiFallback
                result.text = if (role == Role.DISPLAY) "RUN IN PROGRESS\nStart received from phones" else "START RECEIVED\nWaiting for finish trigger…"
            } },
            onResultEvent = { duration, confidence, uncertainty -> runOnUiThread { result.text = "${fmt(duration/1e9)} s\n${if (confidence == 0) "Manual Wi-Fi fallback" else "Confidence $confidence%"} · ±${fmt(uncertainty/1e6)} ms" } },
            onQuality = { offset, delay -> runOnUiThread { sync.text = "Phone clock Δ ${fmt(offset/1000)} s · RTT ${fmt(delay)} ms" } },
            onPeerSeen = { runOnUiThread { if (role == Role.DISPLAY) sync.text = "CONNECTED · receiving phone timing data" } }
        )
        peer.start()
        if (role == Role.DISPLAY) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), 10) else beginSensors()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11,20,16)); setPadding(24,18,24,18) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(24, 18 + bars.top, 24, 18 + bars.bottom)
            insets
        }
        val title = TextView(this).apply { text = "SPRINT / TIMING"; setTextColor(Color.rgb(183,243,75)); textSize = 24f; paint.isFakeBoldText = true }
        val sub = TextView(this).apply { text = if (role == Role.DISPLAY) "Wireless results display · ${Build.MODEL}" else "Source-frame precision · ${Build.MODEL}"; setTextColor(Color.LTGRAY); textSize = 13f }
        val roleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        roleButton = button("").apply { setOnClickListener { role = if (role == Role.START) Role.FINISH else Role.START; updateRole() } }
        arm = button("ARM").apply { setOnClickListener { toggleArm() } }
        manual = button("MANUAL TRIGGER").apply { setOnClickListener { manualTrigger() } }
        cameraChoice = button("CAMERA: FRONT").apply { setOnClickListener { switchCamera() } }
        roleRow.addView(roleButton, LinearLayout.LayoutParams(0,-2,1f)); roleRow.addView(arm, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = 12 })
        val frame = FrameLayout(this)
        preview = TextureView(this)
        frame.addView(preview, FrameLayout.LayoutParams(-1,-1))
        frame.addView(LineOverlay(this), FrameLayout.LayoutParams(-1,-1))
        status = label(if (role == Role.DISPLAY) "DISPLAY MODE · camera and GNSS off" else "Requesting camera and GNSS…")
        sync = label(if (role == Role.DISPLAY) "Searching for timing phones…" else "Peer sync warming up…")
        result = TextView(this).apply { text = if (role == Role.DISPLAY) "WAITING FOR RUN\nResults will appear automatically" else "READY\nArm both phones, then cross the start line."; setTextColor(Color.WHITE); textSize = if (role == Role.DISPLAY) 42f else 22f; gravity = Gravity.CENTER; setPadding(12,20,12,20); setBackgroundColor(Color.rgb(22,38,30)); paint.isFakeBoldText = true }
        root.addView(title); root.addView(sub)
        if (role != Role.DISPLAY) {
            val cameraRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cameraRow.addView(manual, LinearLayout.LayoutParams(0,-2,1f))
            cameraRow.addView(cameraChoice, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = 12 })
            root.addView(roleRow); root.addView(cameraRow, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 10 }); root.addView(frame, LinearLayout.LayoutParams(-1,0,1f).apply { topMargin = 14; bottomMargin = 14 })
        }
        root.addView(status); root.addView(sync); root.addView(result, LinearLayout.LayoutParams(-1, if (role == Role.DISPLAY) 0 else -2, if (role == Role.DISPLAY) 1f else 0f))
        setContentView(root); updateRole()
    }

    private fun button(value: String) = Button(this).apply { text = value; textSize = 16f; setTextColor(Color.rgb(11,20,16)); setBackgroundColor(Color.rgb(183,243,75)); paint.isFakeBoldText = true }
    private fun label(value: String) = TextView(this).apply { text = value; setTextColor(Color.LTGRAY); textSize = 14f; setPadding(0,5,0,5) }
    private fun updateRole() { roleButton.text = "ROLE: ${role.name}" }

    private fun beginSensors() {
        if (preview.isAvailable) startCamera() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) = startCamera()
            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
        }
        location = getSystemService(LocationManager::class.java)
        gnssCallback = object : GnssMeasurementsEvent.Callback() { override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) { gnss.add(event.clock); runOnUiThread { val s = gnss.snapshot; status.text = "${cameraStatus()} · GNSS ${s.state} (${s.samples}, ±${if (s.uncertaintyNanos == Long.MAX_VALUE) "—" else fmt(s.uncertaintyNanos/1e6)} ms)" } } }
        try { location.registerGnssMeasurementsCallback(mainExecutor, gnssCallback!!) } catch (_: SecurityException) {}
    }

    private fun startCamera() { camera = CameraTimingController(this, preview, cameraFacing, { message -> runOnUiThread { status.text = message } }, ::crossing).also { it.start() } }
    private fun cameraStatus() = "${camera?.facingName ?: "Camera"} camera ${camera?.timestampSource ?: "…"}"
    private fun switchCamera() {
        if (armed) { armed = false; arm.text = "ARM" }
        camera?.close(); camera = null
        cameraFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        cameraChoice.text = "CAMERA: ${if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"}"
        status.text = "Switching camera…"
        startCamera()
    }
    private fun toggleArm() { armed = !armed; camera?.setArmed(armed); arm.text = if (armed) "DISARM" else "ARM"; result.text = if (armed) "ARMED · ${role.name}\nWatching the green timing line" else "READY\nArm both phones, then cross the start line." }

    private fun manualTrigger() {
        val now = SystemClock.elapsedRealtimeNanos()
        val mapped = gnss.toGps(now)
        if (role == Role.START) {
            if (mapped != null) {
                startGps = mapped.first; startUncertainty = mapped.second; startUsesWifi = false
                peer.broadcastStart(mapped.first, mapped.second, false)
                result.text = "MANUAL START SENT\nGNSS timing · ±${fmt(mapped.second/1e6)} ms"
            } else {
                val networkUncertainty = peer.roundTripNanos?.div(2)
                if (peer.offsetNanos == null || networkUncertainty == null) { result.text = "MANUAL START REJECTED\nPhone connection not ready"; return }
                startGps = now; startUncertainty = networkUncertainty; startUsesWifi = true
                peer.broadcastStart(now, networkUncertainty, true)
                result.text = "MANUAL START SENT\nWi-Fi fallback · ±${fmt(networkUncertainty/1e6)} ms"
            }
        } else if (role == Role.FINISH) {
            val start = startGps
            if (start == null) { result.text = "MANUAL FINISH REJECTED\nNo start trigger received"; return }
            if (startUsesWifi) {
                finishRun(now, 100, (peer.roundTripNanos ?: 0L) / 2)
            } else if (mapped != null) finishRun(mapped.first, 100, mapped.second)
            else result.text = "MANUAL FINISH REJECTED\nGNSS model not ready"
        }
    }

    private fun crossing(event: LineCrossingDetector.Event) = runOnUiThread {
        if (!armed) return@runOnUiThread
        val mapped = gnss.toGps(event.timestampNanos)
        if (mapped == null) { result.text = "CROSSING DETECTED\nGNSS model not ready — result rejected"; return@runOnUiThread }
        val (gps, uncertainty) = mapped
        if (role == Role.START) {
            startGps = gps; startUncertainty = uncertainty; startUsesWifi = false; peer.broadcastStart(gps, uncertainty, false); result.text = "START SENT\nConfidence ${(event.confidence*100).toInt()}% · ±${fmt(uncertainty/1e6)} ms"
        } else {
            val start = startGps
            if (start == null) result.text = "FINISH DETECTED\nNo start timestamp received" else finishRun(gps, (event.confidence * 100).toInt(), uncertainty)
        }
        armed = false; camera?.setArmed(false); arm.text = "ARM"
    }

    private fun finishRun(finishTime: Long, confidence: Int, finishUncertainty: Long) {
        val start = startGps ?: return
        val duration = finishTime - start
        if (duration <= 0) { result.text = "RESULT REJECTED\nInvalid trigger order"; return }
        val totalUncertainty = finishUncertainty + startUncertainty
        peer.broadcastResult(duration, if (startUsesWifi) 0 else confidence, totalUncertainty)
        result.text = "${fmt(duration/1e9)} s\n${if (startUsesWifi) "Wi-Fi manual fallback" else "Confidence $confidence%"} · ±${fmt(totalUncertainty/1e6)} ms"
        armed = false; camera?.setArmed(false); arm.text = "ARM"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) beginSensors() else status.text = "Camera and precise location permissions are required" }
    override fun onDestroy() { camera?.close(); peer.stop(); if (::location.isInitialized) gnssCallback?.let { location.unregisterGnssMeasurementsCallback(it) }; super.onDestroy() }
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)

    private class LineOverlay(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(183,243,75); strokeWidth = 6f }
        private val shade = Paint().apply { color = Color.argb(40,183,243,75) }
        override fun onDraw(c: android.graphics.Canvas) { val x = width/2f; c.drawRect(x-width*.12f,0f,x+width*.12f,height.toFloat(),shade); c.drawLine(x,0f,x,height.toFloat(),paint); c.drawCircle(x,height*.5f,12f,paint) }
    }
}
