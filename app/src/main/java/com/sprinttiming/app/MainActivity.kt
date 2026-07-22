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
import android.os.Handler
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private enum class Role { START, SPLIT, FINISH, DISPLAY }
    private data class Mark(val role: Int, val localTime: Long, val gpsTime: Long?, val gpsUncertainty: Long, val wifiUncertainty: Long, val confidence: Int)
    private lateinit var preview: TextureView
    private lateinit var previewOverlay: View
    private lateinit var status: TextView
    private lateinit var sync: TextView
    private lateinit var result: TextView
    private lateinit var arm: Button
    private lateinit var manual: Button
    private lateinit var cameraChoice: Button
    private lateinit var previewChoice: Button
    private lateinit var displayArm: Button
    private lateinit var displayPreview: Button
    private lateinit var historyView: TextView
    private lateinit var displayStartSlider: SeekBar
    private lateinit var displayFinishSlider: SeekBar
    private lateinit var displaySplitSlider: SeekBar
    private lateinit var displayStartSensitivity: TextView
    private lateinit var displaySplitSensitivity: TextView
    private lateinit var displayFinishSensitivity: TextView
    private lateinit var roleButton: Button
    private var role = when {
        Build.MANUFACTURER.contains("OnePlus", true) -> Role.FINISH
        Build.MANUFACTURER.contains("Google", true) || Build.MODEL.contains("Pixel", true) -> Role.START
        Build.MANUFACTURER.contains("Huawei", true) || Build.MODEL.contains("EML-L29", true) -> Role.SPLIT
        else -> Role.DISPLAY
    }
    private val gnss = GnssClockModel()
    private var camera: CameraTimingController? = null
    private val marks = mutableMapOf<Int, Mark>()
    private var lastCompletedMarksHash = 0L
    private lateinit var peer: PeerTiming
    private lateinit var location: LocationManager
    private var armed = false
    private var cameraFacing = if (Build.MODEL.contains("Pixel 7", true)) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
    private var previewVisible = true
    private var sensitivity = 50
    private var remoteArmed = false
    private var remotePreviewVisible = true
    private var startArmedState: Boolean? = null
    private var splitArmedState: Boolean? = null
    private var finishArmedState: Boolean? = null
    private var startPreviewState: Boolean? = null
    private var splitPreviewState: Boolean? = null
    private var finishPreviewState: Boolean? = null
    private var lastStatusSent = 0L
    private var gnssCallback: GnssMeasurementsEvent.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        peer = PeerTiming(
            context = applicationContext,
            displayOnly = role == Role.DISPLAY,
            localRole = roleCode(role),
            onTimingEvent = { event -> runOnUiThread { acceptTimingEvent(event) } },
            onResultEvent = { split, total, confidence, splitUncertainty, totalUncertainty, wifiFallback -> runOnUiThread {
                val mode = if (wifiFallback) "Wi-Fi fallback" else "GNSS · confidence $confidence%"
                result.text = "SPLIT ${fmt(split/1e9)} s · ±${fmt(splitUncertainty/1e6)} ms\nFINISH ${fmt(total/1e9)} s · ±${fmt(totalUncertainty/1e6)} ms\n$mode"
                if (role == Role.DISPLAY) appendHistory(split, total, confidence, splitUncertainty, totalUncertainty, wifiFallback)
            } },
            onQuality = { offset, delay -> runOnUiThread { sync.text = "Phone clock Δ ${fmt(offset/1000)} s · RTT ${fmt(delay)} ms" } },
            onPeerSeen = { runOnUiThread { if (role == Role.DISPLAY) sync.text = "CONNECTED · receiving phone timing data" } },
            onControl = { action, value -> runOnUiThread { applyRemoteControl(action, value) } },
            onDisplaySeen = { runOnUiThread { broadcastPhoneStatus() } },
            onDeviceStatus = { deviceRole, isArmed, deviceSensitivity, isPreviewVisible -> runOnUiThread { applyDeviceStatus(deviceRole, isArmed, deviceSensitivity, isPreviewVisible) } }
        )
        peer.start()
        if (role == Role.DISPLAY) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), 10) else beginSensors()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11,20,16)); setPadding(24,18,24,18) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                root.setPadding(24, 18 + bars.top, 24, 18 + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                root.setPadding(24, 18 + insets.systemWindowInsetTop, 24, 18 + insets.systemWindowInsetBottom)
            }
            insets
        }
        val title = TextView(this).apply { text = "SPRINT / TIMING"; setTextColor(Color.rgb(183,243,75)); textSize = 24f; paint.isFakeBoldText = true }
        val sub = TextView(this).apply { text = if (role == Role.DISPLAY) "Wireless results display · ${Build.MODEL}" else "Source-frame precision · ${Build.MODEL}"; setTextColor(Color.LTGRAY); textSize = 13f }
        val roleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        roleButton = button("").apply { setOnClickListener { role = when (role) { Role.START -> Role.SPLIT; Role.SPLIT -> Role.FINISH; else -> Role.START }; updateRole() } }
        arm = button("ARM").apply { setOnClickListener { toggleArm() } }
        manual = button("MANUAL TRIGGER").apply { setOnClickListener { manualTrigger() } }
        cameraChoice = button("CAMERA: ${if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"}").apply { setOnClickListener { switchCamera() } }
        previewChoice = button("PREVIEW: ON").apply { setOnClickListener { setPreviewVisible(!previewVisible, false) } }
        roleRow.addView(roleButton, LinearLayout.LayoutParams(0,-2,1f)); roleRow.addView(arm, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = 12 })
        val frame = FrameLayout(this)
        preview = TextureView(this)
        previewOverlay = LineOverlay(this)
        frame.addView(preview, FrameLayout.LayoutParams(-1,-1))
        frame.addView(previewOverlay, FrameLayout.LayoutParams(-1,-1))
        status = label(if (role == Role.DISPLAY) "DISPLAY MODE · camera and GNSS off" else "Requesting camera and GNSS…")
        sync = label(if (role == Role.DISPLAY) "Searching for timing phones…" else "Peer sync warming up…")
        result = TextView(this).apply { text = if (role == Role.DISPLAY) "WAITING FOR RUN\nSplit and finish results will appear automatically" else "READY · ${role.name}\nArm all three phones, then cross each timing line."; setTextColor(Color.WHITE); textSize = if (role == Role.DISPLAY) 34f else 22f; gravity = Gravity.CENTER; setPadding(12,20,12,20); setBackgroundColor(Color.rgb(22,38,30)); paint.isFakeBoldText = true }
        root.addView(title); root.addView(sub)
        if (role != Role.DISPLAY) {
            val cameraRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cameraRow.addView(manual, LinearLayout.LayoutParams(0,-2,1f))
            cameraRow.addView(cameraChoice, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = 12 })
            root.addView(roleRow); root.addView(cameraRow, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 10 }); root.addView(previewChoice, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 10 }); root.addView(frame, LinearLayout.LayoutParams(-1,0,1f).apply { topMargin = 14; bottomMargin = 14 })
            root.addView(status); root.addView(sync); root.addView(result)
        } else {
            displayArm = button("ARM DEVICES").apply { setOnClickListener { remoteArmed = !remoteArmed; broadcastToPhones(1, if (remoteArmed) 1 else 0); text = if (remoteArmed) "DISARM DEVICES" else "ARM DEVICES" } }
            displayPreview = button("PREVIEW: ON").apply { setOnClickListener { remotePreviewVisible = !remotePreviewVisible; broadcastToPhones(3, if (remotePreviewVisible) 1 else 0); text = "PREVIEW: ${if (remotePreviewVisible) "ON" else "OFF"}" } }
            val remoteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            remoteRow.addView(displayArm, LinearLayout.LayoutParams(0,-2,1f))
            remoteRow.addView(displayPreview, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart = 12 })
            displayStartSensitivity = label("START SENSITIVITY · 50")
            displayStartSlider = sensitivitySlider(1, displayStartSensitivity)
            displaySplitSensitivity = label("SPLIT SENSITIVITY · 50")
            displaySplitSlider = sensitivitySlider(2, displaySplitSensitivity)
            displayFinishSensitivity = label("FINISH SENSITIVITY · 50")
            displayFinishSlider = sensitivitySlider(3, displayFinishSensitivity)
            historyView = label(getPreferences(MODE_PRIVATE).getString("history", "No completed runs yet.") ?: "No completed runs yet.").apply { textSize = 17f }
            val historyScroll = ScrollView(this).apply { addView(historyView) }
            root.addView(status); root.addView(sync); root.addView(remoteRow, LinearLayout.LayoutParams(-1,-2).apply { topMargin = 12 })
            root.addView(displayStartSensitivity); root.addView(displayStartSlider); root.addView(displaySplitSensitivity); root.addView(displaySplitSlider); root.addView(displayFinishSensitivity); root.addView(displayFinishSlider)
            root.addView(result, LinearLayout.LayoutParams(-1,0,0.58f).apply { topMargin = 10 })
            root.addView(label("RESULT HISTORY").apply { setTextColor(Color.rgb(183,243,75)); paint.isFakeBoldText = true })
            root.addView(historyScroll, LinearLayout.LayoutParams(-1,0,0.42f))
        }
        setContentView(root); updateRole()
    }

    private fun button(value: String) = Button(this).apply { text = value; textSize = 16f; setTextColor(Color.rgb(11,20,16)); setBackgroundColor(Color.rgb(183,243,75)); paint.isFakeBoldText = true }
    private fun label(value: String) = TextView(this).apply { text = value; setTextColor(Color.LTGRAY); textSize = 14f; setPadding(0,5,0,5) }
    private fun updateRole() { roleButton.text = "ROLE: ${role.name}" }
    private fun roleCode(value: Role) = when (value) { Role.START -> 1; Role.SPLIT -> 2; Role.FINISH -> 3; Role.DISPLAY -> 4 }

    private fun sensitivitySlider(targetRole: Int, valueLabel: TextView) = SeekBar(this).apply {
        max = 100; progress = 50
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { valueLabel.text = "${roleName(targetRole)} SENSITIVITY · $progress" }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) { peer.broadcastControl(targetRole, 2, seekBar.progress) }
        })
    }

    private fun broadcastToPhones(action: Int, value: Int) { (1..3).forEach { peer.broadcastControl(it, action, value) } }
    private fun roleName(code: Int) = when (code) { 1 -> "START"; 2 -> "SPLIT"; 3 -> "FINISH"; else -> "DISPLAY" }

    private fun applyRemoteControl(action: Int, value: Int) {
        when (action) {
            1 -> setArmedState(value != 0, true)
            2 -> { sensitivity = value.coerceIn(0,100); camera?.setSensitivity(sensitivity); result.text = "SENSITIVITY · $sensitivity\nRemote setting applied"; broadcastPhoneStatus(true) }
            3 -> setPreviewVisible(value != 0, true)
        }
    }

    private fun broadcastPhoneStatus(force: Boolean = false) {
        if (role == Role.DISPLAY || !::peer.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatusSent < 1_000) return
        lastStatusSent = now
        peer.broadcastStatus(roleCode(role), armed, sensitivity, previewVisible)
    }

    private fun applyDeviceStatus(deviceRole: Int, isArmed: Boolean, deviceSensitivity: Int, isPreviewVisible: Boolean) {
        if (role != Role.DISPLAY) return
        if (deviceRole == 1) {
            startArmedState = isArmed; startPreviewState = isPreviewVisible
            displayStartSlider.progress = deviceSensitivity
        } else if (deviceRole == 2) {
            splitArmedState = isArmed; splitPreviewState = isPreviewVisible
            displaySplitSlider.progress = deviceSensitivity
        } else if (deviceRole == 3) {
            finishArmedState = isArmed; finishPreviewState = isPreviewVisible
            displayFinishSlider.progress = deviceSensitivity
        }
        val armStates = listOfNotNull(startArmedState, splitArmedState, finishArmedState)
        remoteArmed = armStates.size == 3 && armStates.all { it }
        displayArm.text = when { armStates.size < 3 -> "ARM DEVICES · CONNECTING"; armStates.all { it } -> "DISARM DEVICES"; armStates.none { it } -> "ARM DEVICES"; else -> "ARM DEVICES · MIXED" }
        val previewStates = listOfNotNull(startPreviewState, splitPreviewState, finishPreviewState)
        remotePreviewVisible = previewStates.size == 3 && previewStates.all { it }
        displayPreview.text = when { previewStates.size < 3 -> "PREVIEW · CONNECTING"; previewStates.all { it } -> "PREVIEW: ON"; previewStates.none { it } -> "PREVIEW: OFF"; else -> "PREVIEW: MIXED" }
    }

    private fun appendHistory(split: Long, total: Long, confidence: Int, splitUncertainty: Long, totalUncertainty: Long, wifiFallback: Boolean) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val mode = if (wifiFallback) "Wi-Fi" else "GNSS $confidence%"
        val entry = "$time · split ${fmt(split/1e9)} s ±${fmt(splitUncertainty/1e6)} ms · finish ${fmt(total/1e9)} s ±${fmt(totalUncertainty/1e6)} ms · $mode"
        val prefs = getPreferences(MODE_PRIVATE)
        val old = prefs.getString("history", "").orEmpty().lineSequence().filter { it.isNotBlank() && it != "No completed runs yet." }.toList()
        val updated = (listOf(entry) + old).take(12).joinToString("\n")
        prefs.edit().putString("history", updated).apply(); historyView.text = updated
    }

    private fun beginSensors() {
        if (preview.isAvailable) startCamera() else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) = startCamera()
            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
        }
        location = getSystemService(LocationManager::class.java)
        gnssCallback = object : GnssMeasurementsEvent.Callback() { override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) { gnss.add(event.clock); runOnUiThread { val s = gnss.snapshot; status.text = "${cameraStatus()} · GNSS ${s.state} (${s.samples}, ±${if (s.uncertaintyNanos == Long.MAX_VALUE) "—" else fmt(s.uncertaintyNanos/1e6)} ms)" } } }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) location.registerGnssMeasurementsCallback(mainExecutor, gnssCallback!!)
            else @Suppress("DEPRECATION") location.registerGnssMeasurementsCallback(gnssCallback!!, Handler(mainLooper))
        } catch (_: SecurityException) {}
    }

    private fun startCamera() { val targetFps = if (Build.MODEL.contains("Pixel 7", true)) 60 else 30; camera = CameraTimingController(this, preview, cameraFacing, targetFps, { message -> runOnUiThread { status.text = message } }, ::crossing).also { it.setSensitivity(sensitivity); it.start() } }
    private fun cameraStatus() = "${camera?.facingName ?: "Camera"} camera ${camera?.timestampSource ?: "…"}${camera?.deliveredFps?.takeIf { it > 0 }?.let { " · ${fmt(it)} fps" } ?: ""}"
    private fun switchCamera() {
        if (armed) { armed = false; arm.text = "ARM" }
        camera?.close(); camera = null
        cameraFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        cameraChoice.text = "CAMERA: ${if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"}"
        status.text = "Switching camera…"
        startCamera()
        broadcastPhoneStatus(true)
    }
    private fun toggleArm() = setArmedState(!armed, false)
    private fun setArmedState(value: Boolean, remote: Boolean) {
        armed = value; camera?.setArmed(value); arm.text = if (value) "DISARM" else "ARM"
        result.text = if (value) "ARMED · ${role.name}\n${if (previewVisible) "Watching the green timing line" else "Preview hidden · detection active"}" else "DISARMED${if (remote) " BY DISPLAY" else ""}\nReady for next run"
        broadcastPhoneStatus(true)
    }

    private fun setPreviewVisible(value: Boolean, remote: Boolean) {
        previewVisible = value
        preview.alpha = if (value) 1f else 0f
        previewOverlay.alpha = if (value) 1f else 0f
        previewChoice.text = "PREVIEW: ${if (value) "ON" else "OFF"}"
        result.text = if (value) "PREVIEW RESTORED${if (remote) " BY DISPLAY" else ""}\nDetection remains ${if (armed) "armed" else "ready"}" else "PREVIEW HIDDEN${if (remote) " BY DISPLAY" else ""}\nDetection remains ${if (armed) "active" else "ready"}"
        broadcastPhoneStatus(true)
    }

    private fun manualTrigger() {
        val now = SystemClock.elapsedRealtimeNanos()
        val mapped = gnss.toGps(now)
        submitTimingEvent(now, mapped, 100, true)
    }

    private fun crossing(event: LineCrossingDetector.Event) = runOnUiThread {
        if (!armed) return@runOnUiThread
        val mapped = gnss.toGps(event.timestampNanos)
        submitTimingEvent(event.timestampNanos, mapped, (event.confidence * 100).toInt(), false)
        armed = false; camera?.setArmed(false); arm.text = "ARM"
        broadcastPhoneStatus(true)
    }

    private fun submitTimingEvent(localTime: Long, mapped: Pair<Long, Long>?, confidence: Int, manual: Boolean) {
        val roleCode = roleCode(role)
        if (roleCode !in 1..3) return
        val uncertainty = mapped?.second ?: 0L
        val mark = TimingEvent(0L, roleCode, localTime, mapped?.first, uncertainty, 0L, confidence)
        result.text = "${if (manual) "MANUAL " else ""}${role.name} TIMESTAMP SENT\n${if (mapped == null) "Wi-Fi fallback" else "GNSS"} · confidence $confidence%"
        acceptTimingEvent(mark)
        peer.broadcastTimingEvent(roleCode, localTime, mapped?.first, uncertainty, confidence)
    }

    private fun acceptTimingEvent(event: TimingEvent) {
        if (role == Role.DISPLAY || event.role !in 1..3) return
        val incoming = Mark(event.role, event.localTimeNanos, event.gpsTimeNanos, event.gpsUncertaintyNanos, event.wifiUncertaintyNanos, event.confidencePercent)
        val existing = marks[event.role]
        if (event.role == 1 && existing != null && kotlin.math.abs(existing.localTime - incoming.localTime) > 1_000_000L) marks.clear()
        marks[event.role] = incoming
        if (marks.size < 3) {
            result.text = "RUN IN PROGRESS\n${marks.size}/3 timestamps received"
            return
        }
        if (role != Role.FINISH) return
        val all = marks.values.toList()
        val marksHash = all.fold(0L) { hash, mark -> hash xor mark.localTime xor (mark.role.toLong() shl 56) }
        if (marksHash == lastCompletedMarksHash) { marks.clear(); return }
        val useGnss = all.all { it.gpsTime != null }
        val calculated = ThreePointTiming.calculate(all.map { TimelineMark(if (useGnss) it.gpsTime!! else it.localTime, if (useGnss) it.gpsUncertainty else it.wifiUncertainty, it.confidence) })
        if (calculated == null) { result.text = "RESULT REJECTED\nInvalid timestamp order"; return }
        peer.broadcastResult(calculated.splitNanos, calculated.totalNanos, calculated.confidencePercent, calculated.splitUncertaintyNanos, calculated.totalUncertaintyNanos, !useGnss)
        lastCompletedMarksHash = marksHash
        result.text = "SPLIT ${fmt(calculated.splitNanos/1e9)} s · ±${fmt(calculated.splitUncertaintyNanos/1e6)} ms\nFINISH ${fmt(calculated.totalNanos/1e9)} s · ±${fmt(calculated.totalUncertaintyNanos/1e6)} ms\n${if (useGnss) "GNSS · confidence ${calculated.confidencePercent}%" else "Wi-Fi fallback"}"
        marks.clear()
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
