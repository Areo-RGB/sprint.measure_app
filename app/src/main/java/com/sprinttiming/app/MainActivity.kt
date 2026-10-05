package com.sprinttiming.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private enum class Role(val code: Int) { START(1), SPLIT(2), FINISH(3), DISPLAY(4) }
    private data class Mark(val role: Int, val localTime: Long, val gpsTime: Long?, val gpsUncertainty: Long, val wifiUncertainty: Long, val detectorUncertainty: Long, val confidence: Int)

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val defaultRole = when {
        Build.MANUFACTURER.contains("OnePlus", true) -> Role.FINISH
        Build.MANUFACTURER.contains("Google", true) || Build.MODEL.contains("Pixel", true) -> Role.START
        Build.MANUFACTURER.contains("Huawei", true) || Build.MODEL.contains("EML-L29", true) -> Role.SPLIT
        else -> Role.DISPLAY
    }
    private var role = defaultRole
    private lateinit var peer: PeerTiming
    private val gnss = GnssClockModel()
    private lateinit var location: LocationManager
    private var gnssCallback: GnssMeasurementsEvent.Callback? = null
    private var gpsListener: LocationListener? = null
    private var camera: CameraTimingController? = null
    private var modes: List<CaptureMode> = emptyList()
    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var cameraMessage = "Starting camera…"
    private var lineFraction = 0.5f
    private var sensitivity = 50
    private var armed = false
    private var previewVisible = true
    private var resumed = false
    private var lastStatusBroadcast = 0L
    private val marks = mutableMapOf<Int, Mark>()
    private val devices = arrayOfNulls<DeviceStatus>(4)

    // Views (phone)
    private lateinit var peersView: TextView
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var roleChip: Button
    private lateinit var cameraChip: Button
    private lateinit var fpsChip: Button
    private lateinit var armButton: Button
    private lateinit var preview: TextureView
    private lateinit var lineOverlay: LineOverlay
    private lateinit var previewFrame: AspectFrame
    // Views (display)
    private val deviceViews = arrayOfNulls<TextView>(4)
    private val sliders = arrayOfNulls<SeekBar>(4)
    private val dragging = BooleanArray(4)
    private lateinit var armAllButton: Button
    private lateinit var previewAllButton: Button
    private lateinit var historyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Timing phones sit unattended; a screen timeout would revoke camera and GNSS access.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (defaultRole != Role.DISPLAY) role = prefs.getString("role", null)?.let { saved -> Role.values().firstOrNull { it.name == saved && it != Role.DISPLAY } } ?: defaultRole
        sensitivity = prefs.getInt("sensitivity", 50)
        lineFraction = prefs.getFloat("line", 0.5f)
        cameraFacing = prefs.getInt("facing", if (Build.MODEL.contains("Pixel 7", true)) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT)
        peer = PeerTiming(applicationContext, role == Role.DISPLAY, role.code, peerListener)
        if (role == Role.DISPLAY) buildDisplayUi() else buildPhoneUi()
        peer.start()
        if (role != Role.DISPLAY) {
            location = getSystemService(LocationManager::class.java)
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startCamera() }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
            if (!hasSensorPermissions()) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), 10)
        }
        uiHandler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (role != Role.DISPLAY && hasSensorPermissions()) { startGnss(); startCamera() }
    }

    override fun onPause() {
        resumed = false
        if (role != Role.DISPLAY) {
            if (armed) setArmed(false, note = "App left the foreground")
            closeCamera()
            stopGnss()
        }
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        closeCamera()
        peer.stop()
        if (::location.isInitialized) stopGnss()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) { if (resumed) { startGnss(); startCamera() } }
        else cameraMessage = "Camera and precise location permissions are required"
    }

    private fun hasSensorPermissions() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- UI

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BACKGROUND)
        setPadding(24, 18, 24, 18)
        setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(24, 18 + bars.top, 24, 18 + bars.bottom)
            } else @Suppress("DEPRECATION") v.setPadding(24, 18 + insets.systemWindowInsetTop, 24, 18 + insets.systemWindowInsetBottom)
            insets
        }
    }

    private fun header(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply { text = title; setTextColor(ACCENT); textSize = 20f; paint.isFakeBoldText = true }, LinearLayout.LayoutParams(0, -2, 1f))
        peersView = label("● 0 linked").apply { gravity = Gravity.END }
        addView(peersView)
    }

    private fun button(value: String, primary: Boolean = false) = Button(this).apply {
        text = value; textSize = if (primary) 20f else 14f; isAllCaps = true; paint.isFakeBoldText = true
        setTextColor(if (primary) BACKGROUND else ACCENT)
        background = GradientDrawable().apply { cornerRadius = 18f; setColor(if (primary) ACCENT else PANEL) }
        stateListAnimator = null
    }

    private fun label(value: String) = TextView(this).apply { text = value; setTextColor(Color.LTGRAY); textSize = 13f; setPadding(0, 4, 0, 4) }

    private fun resultPanel(size: Float) = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER; setPadding(12, 18, 12, 18); paint.isFakeBoldText = true
        background = GradientDrawable().apply { cornerRadius = 18f; setColor(PANEL) }
    }

    private fun row(vararg views: View, weights: FloatArray = FloatArray(views.size) { 1f }) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, weights[i]).apply { if (i > 0) marginStart = 12 }) }
    }

    private fun buildPhoneUi() {
        val root = root()
        root.addView(header("SPRINT TIMING"))
        roleChip = button("").apply { setOnClickListener { cycleRole() } }
        cameraChip = button("").apply { setOnClickListener { cycleCamera() } }
        fpsChip = button("— FPS").apply { setOnClickListener { cycleMode() } }
        root.addView(row(roleChip, cameraChip, fpsChip), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
        preview = TextureView(this)
        lineOverlay = LineOverlay(this, lineFraction) { fraction ->
            lineFraction = fraction
            prefs.edit().putFloat("line", fraction).apply()
            camera?.setLine(fraction)
        }
        previewFrame = AspectFrame(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(lineOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(FrameLayout(this).apply { addView(previewFrame, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL)) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 12; bottomMargin = 8 })
        statusView = label("").apply { textSize = 12f }
        root.addView(statusView)
        resultView = resultPanel(22f)
        root.addView(resultView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
        armButton = button("ARM", primary = true).apply { setOnClickListener { setArmed(!armed) } }
        val manual = button("MANUAL").apply { setOnClickListener { submitTimingEvent(SystemClock.elapsedRealtimeNanos(), 0L, 100, manual = true) } }
        root.addView(row(armButton, manual, weights = floatArrayOf(2f, 1f)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
        setContentView(root)
        refreshChips()
        showResult("READY · ${role.name}", "Drag the line onto the timing mark, then ARM")
    }

    private fun buildDisplayUi() {
        val root = root()
        root.addView(header("SPRINT TIMING · RESULTS"))
        for (code in 1..3) {
            deviceViews[code] = label("${roleName(code)} · offline").apply { textSize = 14f; setPadding(0, 10, 0, 0) }
            sliders[code] = SeekBar(this).apply {
                max = 100; progress = 50
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) refreshDevices() }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { dragging[code] = true }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { dragging[code] = false; peer.broadcastControl(code, 2, seekBar.progress) }
                })
            }
            root.addView(deviceViews[code]); root.addView(sliders[code])
        }
        armAllButton = button("ARM ALL", primary = true).apply {
            setOnClickListener { peer.broadcastControl(0, 1, if (allArmed()) 0 else 1) }
        }
        previewAllButton = button("PREVIEW").apply {
            setOnClickListener { val on = (1..3).mapNotNull { liveDevice(it) }.any { it.preview }; peer.broadcastControl(0, 3, if (on) 0 else 1) }
        }
        root.addView(row(armAllButton, previewAllButton, weights = floatArrayOf(2f, 1f)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
        resultView = resultPanel(34f)
        root.addView(resultView, LinearLayout.LayoutParams(-1, 0, 0.55f).apply { topMargin = 12 })
        root.addView(label("HISTORY · long-press to clear").apply { setTextColor(ACCENT); paint.isFakeBoldText = true; setPadding(0, 12, 0, 4) })
        historyView = label(prefs.getString("history", null) ?: "No completed runs yet.").apply { textSize = 16f }
        val scroll = ScrollView(this).apply { addView(historyView) }
        historyView.setOnLongClickListener {
            prefs.edit().remove("history").apply(); historyView.text = "No completed runs yet."
            toast("History cleared"); true
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 0.45f))
        setContentView(root)
        showResult("WAITING FOR RUN", "Results appear automatically")
    }

    private fun showResult(title: String, detail: String) { resultView.text = "$title\n$detail" }

    private fun refreshChips() {
        roleChip.text = role.name
        cameraChip.text = if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) "FRONT" else "BACK"
        fpsChip.text = "${camera?.mode?.label ?: "—"} FPS"
        armButton.text = if (armed) "DISARM" else "ARM"
        (armButton.background as? GradientDrawable)?.setColor(if (armed) ALERT else ACCENT)
        lineOverlay.locked = armed
    }

    /** Periodic refresh of status text and phone → display status heartbeats. */
    private val ticker = object : Runnable {
        override fun run() {
            val peers = peer.peerCount()
            peersView.text = "● $peers linked"
            peersView.setTextColor(if (peers >= 3) ACCENT else WARN)
            if (role == Role.DISPLAY) refreshDevices() else {
                refreshStatus()
                if (SystemClock.elapsedRealtime() - lastStatusBroadcast >= 2_000L) broadcastStatus()
            }
            uiHandler.postDelayed(this, 500L)
        }
    }

    private fun refreshStatus() {
        val cam = camera
        val cameraLine = if (cam != null && cam.ready) cam.summary() + (if (cam.gpuPreviewMissing) " · preview unavailable" else "") else cameraMessage
        val now = SystemClock.elapsedRealtimeNanos()
        val g = gnss.snapshot
        val gnssLine = when (val state = gnss.stateAt(now)) {
            GnssClockModel.State.LOCKED, GnssClockModel.State.DEGRADED -> "GNSS $state · ±${ms(g.uncertaintyNanos)} ms · ${g.samples} samples"
            GnssClockModel.State.ACQUIRING -> "GNSS ACQUIRING (${g.samples}/${GnssClockModel.MIN_SAMPLES}) · needs open sky"
            else -> "GNSS $state"
        }
        val sync = peer.syncUncertaintyNanos()
        val wifiLine = "Wi-Fi · ${peer.peerCount()} linked" + (sync?.let { " · sync ±${ms(it)} ms" } ?: " · syncing…")
        statusView.text = "$cameraLine\n$gnssLine\n$wifiLine"
    }

    // ---------------------------------------------------------------- phone controls

    private fun cycleRole() {
        if (armed) { toast("Disarm first"); return }
        role = when (role) { Role.START -> Role.SPLIT; Role.SPLIT -> Role.FINISH; else -> Role.START }
        prefs.edit().putString("role", role.name).apply()
        peer.localRole = role.code
        marks.clear()
        refreshChips()
        showResult("READY · ${role.name}", "Role changed")
        broadcastStatus()
    }

    private fun cycleCamera() {
        if (armed) { toast("Disarm first"); return }
        cameraFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_FRONT) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        prefs.edit().putInt("facing", cameraFacing).apply()
        restartCamera()
    }

    private fun cycleMode() {
        if (armed) { toast("Disarm first"); return }
        val current = camera?.mode ?: return
        if (modes.size < 2) { toast("Only ${current.label} fps is available on this camera"); return }
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        prefs.edit().putString("mode_$cameraFacing", next.key).apply()
        restartCamera()
    }

    private fun startCamera() {
        if (camera != null || !resumed || !::preview.isInitialized || !preview.isAvailable || !hasSensorPermissions()) return
        val manager = getSystemService(CameraManager::class.java)
        try {
            val id = CameraTimingController.cameraFor(manager, cameraFacing) ?: run { cameraMessage = "No camera found"; return }
            modes = CameraTimingController.modes(manager, id)
            val saved = prefs.getString("mode_$cameraFacing", null)
            val mode = modes.firstOrNull { it.key == saved }
                ?: modes.firstOrNull { it.fps == 60 && !it.highSpeed }
                ?: modes.filter { !it.highSpeed }.maxByOrNull { it.fps } ?: modes.first()
            camera = CameraTimingController(this, preview, id, mode, { message -> runOnUiThread { cameraMessage = message } }, ::crossing, ::cameraLost).also {
                previewFrame.aspect = it.uprightAspect
                it.setSensitivity(sensitivity)
                it.setLine(lineFraction)
                it.setPreviewEnabled(previewVisible)
                it.start()
            }
        } catch (e: Exception) {
            cameraMessage = "Camera unavailable (${e.javaClass.simpleName})"
        }
        refreshChips()
    }

    private fun closeCamera() { camera?.close(); camera = null }

    private fun restartCamera() {
        if (armed) setArmed(false)
        closeCamera()
        cameraMessage = "Switching camera…"
        startCamera()
        broadcastStatus()
    }

    private fun cameraLost(lost: CameraTimingController) = runOnUiThread {
        if (camera !== lost) return@runOnUiThread
        if (armed) setArmed(false, note = "Camera lost · reopening")
        lost.close(); camera = null
        if (lost.mode.highSpeed && lost.configurationRejected) {
            // This device refused the high-speed session: fall back to the best normal mode.
            val fallback = modes.filter { !it.highSpeed }.maxByOrNull { it.fps }
            if (fallback != null) prefs.edit().putString("mode_$cameraFacing", fallback.key).apply()
            toast("${lost.mode.label} fps not usable here · using ${fallback?.label ?: "default"} fps")
        }
        broadcastStatus()
        uiHandler.postDelayed({ startCamera() }, 1500L)
    }

    private fun setArmed(value: Boolean, remote: Boolean = false, note: String? = null) {
        if (value && camera?.ready != true) { showResult("CAMERA NOT READY", cameraMessage); broadcastStatus(); return }
        armed = value
        camera?.setArmed(value)
        refreshChips()
        if (value) showResult("ARMED · ${role.name}", if (previewVisible) "Exposure locked · watching the line" else "Preview hidden · detection active")
        else showResult("DISARMED", note ?: if (remote) "By display" else "Ready for next run")
        broadcastStatus()
    }

    private fun setPreviewVisible(value: Boolean) {
        previewVisible = value
        preview.alpha = if (value) 1f else 0f
        lineOverlay.alpha = if (value) 1f else 0f
        camera?.setPreviewEnabled(value)
        broadcastStatus()
    }

    private fun broadcastStatus() {
        if (role == Role.DISPLAY) return
        lastStatusBroadcast = SystemClock.elapsedRealtime()
        val g = gnss.snapshot
        val state = gnss.stateAt(SystemClock.elapsedRealtimeNanos())
        val micros = if (g.uncertaintyNanos == Long.MAX_VALUE) -1 else (g.uncertaintyNanos / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        peer.broadcastStatus(DeviceStatus(role.code, armed, sensitivity, previewVisible, camera?.deliveredFps?.toFloat() ?: 0f, state.ordinal, micros, camera?.ready == true))
    }

    // ---------------------------------------------------------------- timing

    private fun crossing(event: LineCrossingDetector.Event) = runOnUiThread {
        if (!armed) return@runOnUiThread
        armed = false
        camera?.setArmed(false)
        refreshChips()
        submitTimingEvent(event.timestampNanos, event.uncertaintyNanos, (event.confidence * 100).toInt(), manual = false)
        broadcastStatus()
    }

    private fun submitTimingEvent(localTime: Long, detectorUncertainty: Long, confidence: Int, manual: Boolean) {
        if (role == Role.DISPLAY) return
        val mapped = gnss.toGps(localTime)
        showResult("${if (manual) "MANUAL " else ""}${role.name} SENT", "${if (mapped == null) "Wi-Fi clock" else "GNSS"} · ±${ms(detectorUncertainty)} ms · $confidence%")
        acceptTimingEvent(TimingEvent(0L, role.code, localTime, mapped?.first, mapped?.second ?: 0L, 0L, detectorUncertainty, confidence))
        peer.broadcastTimingEvent(role.code, localTime, mapped?.first, mapped?.second ?: 0L, detectorUncertainty, confidence)
    }

    private fun acceptTimingEvent(event: TimingEvent) {
        if (role == Role.DISPLAY || event.role !in 1..3) return
        val incoming = Mark(event.role, event.localTimeNanos, event.gpsTimeNanos, event.gpsUncertaintyNanos, event.wifiUncertaintyNanos, event.detectorUncertaintyNanos, event.confidencePercent)
        // Events are unique per sender. A START opens a new run: drop anything older (an aborted
        // run), but keep marks that are later than it in case they arrived before the START packet.
        if (event.role == 1) marks.entries.removeAll { it.value.localTime < incoming.localTime }
        marks[event.role] = incoming
        if (marks.size < 3) { if (event.senderId != 0L) showResult("RUN IN PROGRESS", "${marks.size}/3 timestamps received"); return }
        if (role != Role.FINISH) { showResult("RUN COMPLETE", "Waiting for the FINISH phone"); return }
        val all = marks.values.toList()
        marks.clear()
        val gnssMarks = if (all.all { it.gpsTime != null }) all.map { TimelineMark(it.gpsTime!!, ThreePointTiming.rss(it.gpsUncertainty, it.detectorUncertainty), it.confidence) } else null
        val wifiMarks = all.map { TimelineMark(it.localTime, ThreePointTiming.rss(it.wifiUncertainty, it.detectorUncertainty), it.confidence) }
        val choice = ThreePointTiming.choose(gnssMarks, wifiMarks) ?: run { showResult("RESULT REJECTED", "Invalid timestamp order"); return }
        val result = RunResult(choice.result.splitNanos, choice.result.totalNanos, choice.result.splitUncertaintyNanos, choice.result.totalUncertaintyNanos,
            choice.result.confidencePercent, choice.gnss, choice.disagreementNanos)
        peer.broadcastResult(result)
        showRunResult(result)
    }

    private fun showRunResult(result: RunResult) {
        val basis = if (result.gnss) "GNSS" else "Wi-Fi"
        val warning = if (result.confidencePercent <= 50 && result.disagreementNanos != null) " · ⚠ GNSS/Wi-Fi differ ${ms(result.disagreementNanos)} ms" else ""
        resultView.text = "SPLIT ${sec(result.splitNanos)} s  ±${ms(result.splitUncertaintyNanos)} ms\n" +
            "FINISH ${sec(result.totalNanos)} s  ±${ms(result.totalUncertaintyNanos)} ms\n$basis · ${result.confidencePercent}%$warning"
    }

    private fun appendHistory(result: RunResult) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "$time · split ${sec(result.splitNanos)} · finish ${sec(result.totalNanos)} s ±${ms(result.totalUncertaintyNanos)} ms · ${if (result.gnss) "GNSS" else "Wi-Fi"} ${result.confidencePercent}%"
        val old = prefs.getString("history", "").orEmpty().lineSequence().filter { it.isNotBlank() && it != "No completed runs yet." }.toList()
        val updated = (listOf(entry) + old).take(50).joinToString("\n")
        prefs.edit().putString("history", updated).apply()
        historyView.text = updated
    }

    // ---------------------------------------------------------------- network callbacks

    private val peerListener = object : PeerTiming.Listener {
        override fun onTimingEvent(event: TimingEvent) = runOnUiThread { acceptTimingEvent(event) }
        override fun onResult(result: RunResult) = runOnUiThread {
            showRunResult(result)
            if (role == Role.DISPLAY) appendHistory(result) else marks.clear()
        }
        override fun onControl(action: Int, value: Int) = runOnUiThread {
            when (action) {
                1 -> if ((value != 0) != armed) setArmed(value != 0, remote = true)
                2 -> {
                    sensitivity = value.coerceIn(0, 100)
                    prefs.edit().putInt("sensitivity", sensitivity).apply()
                    camera?.setSensitivity(sensitivity)
                    broadcastStatus()
                }
                3 -> setPreviewVisible(value != 0)
            }
        }
        override fun onDeviceStatus(status: DeviceStatus) = runOnUiThread {
            if (status.role in 1..3) { devices[status.role] = status; refreshDevices() }
        }
        override fun onNetworkError(message: String) = runOnUiThread {
            if (role == Role.DISPLAY) showResult("NETWORK ERROR", message) else cameraMessage = message
            toast(message)
        }
    }

    // ---------------------------------------------------------------- display

    private fun liveDevice(code: Int) = devices[code]?.takeIf { SystemClock.elapsedRealtime() - it.receivedAtMillis < 6_000L }

    private fun allArmed() = (1..3).all { liveDevice(it)?.armed == true }

    private fun refreshDevices() {
        for (code in 1..3) {
            val view = deviceViews[code] ?: continue
            val slider = sliders[code] ?: continue
            val d = liveDevice(code)
            if (d == null) { view.text = "${roleName(code)} · offline"; view.setTextColor(Color.GRAY); continue }
            if (!dragging[code]) slider.progress = d.sensitivity
            val gnssState = GnssClockModel.State.values().getOrNull(d.gnssState)
            val gnssText = if (d.gnssUncertaintyMicros >= 0 && (gnssState == GnssClockModel.State.LOCKED || gnssState == GnssClockModel.State.DEGRADED))
                "GNSS ±${String.format(Locale.US, "%.2f", d.gnssUncertaintyMicros / 1000.0)} ms" else "GNSS ${gnssState ?: "?"}"
            val cameraText = if (d.cameraReady) "${String.format(Locale.US, "%.0f", d.fps)} fps" else "camera off"
            view.text = "${roleName(code)} · ${if (d.armed) "● ARMED" else "○ ready"} · $cameraText · $gnssText · sens ${if (dragging[code]) slider.progress else d.sensitivity}"
            view.setTextColor(if (d.armed) ACCENT else Color.LTGRAY)
        }
        val online = (1..3).mapNotNull { liveDevice(it) }
        armAllButton.text = when { online.isEmpty() -> "ARM ALL · NO PHONES"; allArmed() -> "DISARM ALL"; else -> "ARM ALL (${online.size}/3)" }
        (armAllButton.background as? GradientDrawable)?.setColor(if (allArmed()) ALERT else ACCENT)
        previewAllButton.text = if (online.any { it.preview }) "PREVIEW OFF" else "PREVIEW ON"
    }

    // ---------------------------------------------------------------- GNSS

    private fun startGnss() {
        if (gnssCallback != null) return
        val callback = object : GnssMeasurementsEvent.Callback() { override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) = gnss.add(event.clock) }
        // Most chipsets only deliver raw GNSS measurements while a GPS location request keeps the engine on.
        // All four methods are overridden because API 29 has no default implementations.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { toast("GPS is off · enable Location for GNSS timing") }
            @Suppress("OVERRIDE_DEPRECATION") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, mainLooper)
            gpsListener = listener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) location.registerGnssMeasurementsCallback(mainExecutor, callback)
            else @Suppress("DEPRECATION") location.registerGnssMeasurementsCallback(callback, uiHandler)
            gnssCallback = callback
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) { toast("No GPS provider on this device") }
    }

    private fun stopGnss() {
        gnssCallback?.let { location.unregisterGnssMeasurementsCallback(it) }; gnssCallback = null
        gpsListener?.let { location.removeUpdates(it) }; gpsListener = null
    }

    // ---------------------------------------------------------------- helpers

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun roleName(code: Int) = Role.values().firstOrNull { it.code == code }?.name ?: "?"
    private fun ms(nanos: Long) = String.format(Locale.US, "%.2f", nanos / 1e6)
    private fun sec(nanos: Long) = String.format(Locale.US, "%.3f", nanos / 1e9)

    /** Keeps the preview at the camera's upright aspect ratio so the line is not distorted. */
    private class AspectFrame(context: Context) : FrameLayout(context) {
        var aspect = 3f / 4f
            set(value) { if (value > 0f && value != field) { field = value; requestLayout() } }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec); val maxH = MeasureSpec.getSize(heightMeasureSpec)
            var w = maxW; var h = (maxW / aspect).toInt()
            if (maxH in 1 until h) { h = maxH; w = (maxH * aspect).toInt() }
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    /** Draggable timing line with the detector's analysis strip; locked (and red) while armed. */
    private class LineOverlay(context: Context, private var fraction: Float, private val onMoved: (Float) -> Unit) : View(context) {
        var locked = false
            set(value) { field = value; invalidate() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; style = Paint.Style.STROKE }
        private val strip = Paint().apply { color = Color.argb(36, 183, 243, 75) }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER; setShadowLayer(4f, 0f, 0f, Color.BLACK) }

        override fun onDraw(c: Canvas) {
            val x = width * fraction
            c.drawRect(x - width * 0.2f, height * 0.08f, x + width * 0.2f, height * 0.92f, strip)
            line.color = if (locked) ALERT else ACCENT
            c.drawLine(x, 0f, x, height.toFloat(), line)
            c.drawCircle(x, height * 0.5f, 16f, line)
            if (!locked) c.drawText("drag to align", x, height - 24f, hint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (locked || width == 0) return false
            fraction = (event.x / width).coerceIn(0.1f, 0.9f)
            invalidate()
            if (event.actionMasked == MotionEvent.ACTION_UP) { performClick(); onMoved(fraction) }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private companion object {
        val BACKGROUND = Color.rgb(11, 20, 16)
        val PANEL = Color.rgb(22, 38, 30)
        val ACCENT = Color.rgb(183, 243, 75)
        val ALERT = Color.rgb(255, 99, 82)
        val WARN = Color.rgb(255, 196, 61)
    }
}
