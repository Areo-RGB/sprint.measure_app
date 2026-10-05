package com.sprinttiming.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A crossing from one phone, already converted to this phone's elapsed-realtime clock. */
data class TimingEvent(
    val senderId: Long,
    val role: Int,
    val localTimeNanos: Long,
    val gpsTimeNanos: Long?,
    val gpsUncertaintyNanos: Long,
    val wifiUncertaintyNanos: Long,
    val detectorUncertaintyNanos: Long,
    val confidencePercent: Int
)

data class RunResult(
    val splitNanos: Long,
    val totalNanos: Long,
    val splitUncertaintyNanos: Long,
    val totalUncertaintyNanos: Long,
    val confidencePercent: Int,
    val gnss: Boolean,
    val disagreementNanos: Long?
)

data class DeviceStatus(
    val role: Int,
    val armed: Boolean,
    val sensitivity: Int,
    val preview: Boolean,
    val fps: Float,
    val gnssState: Int,
    val gnssUncertaintyMicros: Int,
    val cameraReady: Boolean,
    val receivedAtMillis: Long = SystemClock.elapsedRealtime()
)

/**
 * UDP link between the timing phones and the display (port 48123, versioned binary packets).
 * One receive thread timestamps packets on arrival; one scheduler thread sends without blocking
 * sleeps, so a long rebroadcast never delays results, controls or clock exchanges. Each phone runs
 * four-timestamp exchanges with every peer (4 Hz) into a per-peer [PeerClock].
 */
class PeerTiming(context: Context, private val displayOnly: Boolean, localRole: Int, private val listener: Listener) {
    interface Listener {
        fun onTimingEvent(event: TimingEvent)
        fun onResult(result: RunResult)
        fun onControl(action: Int, value: Int)
        fun onDeviceStatus(status: DeviceStatus)
        fun onNetworkError(message: String)
    }

    private class Node(@Volatile var address: InetAddress, @Volatile var lastSeen: Long)
    private class PendingEvent(val sender: Long, val seq: Int, val role: Int, val localTime: Long, val gpsTime: Long, val gpsUncertainty: Long, val detectorUncertainty: Long, val confidence: Int)

    @Volatile var localRole = localRole
    private val multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("sprint-timing")?.apply { setReferenceCounted(false) }
    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "peer-send").apply { isDaemon = true } }
    private var receiver: Thread? = null
    @Volatile private var socket: DatagramSocket? = null
    private val nodeId = UUID.randomUUID().mostSignificantBits
    private val nodes = ConcurrentHashMap<Long, Node>()
    private val clocks = ConcurrentHashMap<Long, PeerClock>()
    private val pendingEvents = ConcurrentHashMap<Long, PendingEvent>()
    private val deliveredSeq = ConcurrentHashMap<Long, Int>()
    private val resultSeq = ConcurrentHashMap<Long, Int>()
    private val eventSeq = AtomicInteger(0)
    private val ticks = AtomicInteger(0)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try { multicastLock?.acquire() } catch (_: Exception) { }
        receiver = Thread({ receiveLoop() }, "peer-receive").apply { isDaemon = true; start() }
        scheduler.scheduleWithFixedDelay({ safely { tick() } }, 0L, TICK_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running.set(false)
        socket?.close()
        receiver?.interrupt()
        scheduler.shutdownNow()
        try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Exception) { }
    }

    private fun receiveLoop() {
        val s = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(PORT)) }
        } catch (e: Exception) {
            listener.onNetworkError("UDP port $PORT unavailable (${e.message ?: e.javaClass.simpleName})")
            return
        }
        socket = s
        val buffer = ByteArray(256)
        try {
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    val arrival = SystemClock.elapsedRealtimeNanos()
                    safely { receive(s, packet, arrival) }
                } catch (_: SocketTimeoutException) { }
            }
        } catch (e: Exception) {
            if (running.get()) listener.onNetworkError("Network receive stopped (${e.javaClass.simpleName})")
        } finally { s.close() }
    }

    private fun header(type: Int, size: Int) = ByteBuffer.allocate(HEADER + size).put(MAGIC).put(VERSION).put(type.toByte()).putLong(nodeId)

    private fun receive(s: DatagramSocket, packet: DatagramPacket, arrival: Long) {
        val b = ByteBuffer.wrap(packet.data, 0, packet.length)
        if (b.remaining() < HEADER || b.get() != MAGIC || b.get() != VERSION) return
        val type = b.get().toInt()
        val sender = b.long
        if (sender == nodeId) return
        val now = SystemClock.elapsedRealtime()
        nodes.getOrPut(sender) { Node(packet.address, now) }.apply { address = packet.address; lastSeen = now }
        when (type) {
            SYNC_REQUEST -> if (!displayOnly && b.remaining() >= 8) {
                val t1 = b.long
                val reply = header(SYNC_RESPONSE, 32).putLong(sender).putLong(t1).putLong(arrival).putLong(SystemClock.elapsedRealtimeNanos()).array()
                try { s.send(DatagramPacket(reply, reply.size, packet.address, PORT)) } catch (_: Exception) { }
            }
            SYNC_RESPONSE -> if (!displayOnly && b.remaining() >= 32) {
                if (b.long != nodeId) return
                val t1 = b.long; val t2 = b.long; val t3 = b.long
                clocks.getOrPut(sender) { PeerClock() }.add(t1, t2, t3, arrival)
                pendingEvents[sender]?.let { if (deliver(it)) pendingEvents.remove(sender, it) }
            }
            EVENT -> if (!displayOnly && b.remaining() >= 41) {
                val event = PendingEvent(sender, b.int, b.get().toInt(), b.long, b.long, b.long, b.long, b.int)
                if (event.seq <= (deliveredSeq[sender] ?: 0)) return
                if (!deliver(event)) { pendingEvents[sender] = event; syncBurst(packet.address) }
            }
            RESULT -> if (b.remaining() >= 49) {
                val seq = b.int
                if (seq <= (resultSeq[sender] ?: 0)) return
                resultSeq[sender] = seq
                val split = b.long; val total = b.long; val splitU = b.long; val totalU = b.long
                val confidence = b.int; val gnss = b.get().toInt() != 0; val disagreement = b.long
                listener.onResult(RunResult(split, total, splitU, totalU, confidence, gnss, disagreement.takeIf { it >= 0 }))
            }
            CONTROL -> if (!displayOnly && b.remaining() >= 6) {
                val target = b.get().toInt(); val action = b.get().toInt(); val value = b.int
                if (target == 0 || target == localRole) listener.onControl(action, value)
            }
            STATUS -> if (displayOnly && b.remaining() >= 15) {
                val role = b.get().toInt(); val armed = b.get().toInt() != 0; val sensitivity = b.int; val preview = b.get().toInt() != 0
                val fps = b.short / 10f; val gnssState = b.get().toInt(); val gnssMicros = b.int; val ready = b.get().toInt() != 0
                listener.onDeviceStatus(DeviceStatus(role, armed, sensitivity, preview, fps, gnssState, gnssMicros, ready))
            }
        }
    }

    /** Converts a remote event into the local clock with the drift-tracked offset at that instant. */
    private fun deliver(event: PendingEvent): Boolean {
        if (event.seq <= (deliveredSeq[event.sender] ?: 0)) return true
        val clock = clocks[event.sender] ?: return false
        val rough = clock.estimate(SystemClock.elapsedRealtimeNanos()) ?: return false
        val estimate = clock.estimate(event.localTime - rough.offsetNanos) ?: rough
        deliveredSeq[event.sender] = event.seq
        listener.onTimingEvent(TimingEvent(
            senderId = event.sender,
            role = event.role,
            localTimeNanos = event.localTime - estimate.offsetNanos,
            gpsTimeNanos = event.gpsTime.takeIf { it != 0L },
            gpsUncertaintyNanos = event.gpsUncertainty,
            wifiUncertaintyNanos = estimate.uncertaintyNanos,
            detectorUncertaintyNanos = event.detectorUncertainty,
            confidencePercent = event.confidence
        ))
        return true
    }

    private fun tick() {
        val tick = ticks.getAndIncrement()
        val sweep = tick % SWEEP_EVERY_TICKS == 0 && peerCount() < EXPECTED_PEERS
        if (displayOnly) { if (tick % 2 == 0) sendAll(header(HELLO, 1).put(localRole.toByte()).array(), sweep) }
        else {
            // Unicast exchanges to known peers, each stamped immediately before its own send.
            for (node in liveNodes()) sendSyncRequest(node.address)
            if (tick % 8 == 0 || sweep) sendAll(header(SYNC_REQUEST, 8).putLong(SystemClock.elapsedRealtimeNanos()).array(), sweep)
        }
        val cutoff = SystemClock.elapsedRealtime() - NODE_EXPIRY_MILLIS
        nodes.entries.removeIf { it.value.lastSeen < cutoff }
    }

    private fun sendSyncRequest(address: InetAddress) {
        val s = socket ?: return
        val data = header(SYNC_REQUEST, 8).putLong(SystemClock.elapsedRealtimeNanos()).array()
        try { s.send(DatagramPacket(data, data.size, address, PORT)) } catch (_: Exception) { }
    }

    /** A few quick exchanges so a peer without a clock estimate yet can be converted promptly. */
    private fun syncBurst(address: InetAddress) { for (i in 0 until 4) scheduler.schedule({ safely { sendSyncRequest(address) } }, i * 30L, TimeUnit.MILLISECONDS) }

    fun broadcastTimingEvent(role: Int, localTime: Long, gpsTime: Long?, gpsUncertainty: Long, detectorUncertainty: Long, confidence: Int) = repeatSend(
        header(EVENT, 41).putInt(eventSeq.incrementAndGet()).put(role.toByte()).putLong(localTime).putLong(gpsTime ?: 0L)
            .putLong(gpsUncertainty).putLong(detectorUncertainty).putInt(confidence).array(),
        count = 16, gapMillis = 250, sweepCopies = 2
    )

    fun broadcastResult(result: RunResult) = repeatSend(
        header(RESULT, 49).putInt(eventSeq.incrementAndGet()).putLong(result.splitNanos).putLong(result.totalNanos)
            .putLong(result.splitUncertaintyNanos).putLong(result.totalUncertaintyNanos).putInt(result.confidencePercent)
            .put(if (result.gnss) 1 else 0).putLong(result.disagreementNanos ?: -1L).array(),
        count = 8, gapMillis = 150, sweepCopies = 1
    )

    /** [targetRole] 0 addresses every timing phone. Actions: 1 arm (value 0/1), 2 sensitivity, 3 preview (0/1). */
    fun broadcastControl(targetRole: Int, action: Int, value: Int) =
        repeatSend(header(CONTROL, 6).put(targetRole.toByte()).put(action.toByte()).putInt(value).array(), count = 4, gapMillis = 80)

    fun broadcastStatus(status: DeviceStatus) = repeatSend(
        header(STATUS, 15).put(status.role.toByte()).put(if (status.armed) 1 else 0).putInt(status.sensitivity).put(if (status.preview) 1 else 0)
            .putShort((status.fps * 10).toInt().toShort()).put(status.gnssState.toByte()).putInt(status.gnssUncertaintyMicros).put(if (status.cameraReady) 1 else 0).array(),
        count = 1, gapMillis = 0
    )

    private fun repeatSend(data: ByteArray, count: Int, gapMillis: Long, sweepCopies: Int = 0) {
        if (!running.get()) return
        for (i in 0 until count) try {
            scheduler.schedule({ safely { sendAll(data, i < sweepCopies) } }, i * gapMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { }
    }

    /** Known peers + broadcast addresses; [sweep] also unicasts the local subnet for hotspots that drop broadcasts. */
    private fun sendAll(data: ByteArray, sweep: Boolean) {
        val s = socket ?: return
        val targets = LinkedHashSet<InetAddress>()
        liveNodes().mapTo(targets) { it.address }
        targets.addAll(broadcastAddresses())
        if (sweep) targets.addAll(localSubnetAddresses())
        for (target in targets) try { s.send(DatagramPacket(data, data.size, target, PORT)) } catch (_: Exception) { }
    }

    private fun liveNodes(): List<Node> {
        val cutoff = SystemClock.elapsedRealtime() - LIVE_MILLIS
        return nodes.values.filter { it.lastSeen >= cutoff }
    }

    fun peerCount() = liveNodes().size

    /** Worst current Wi-Fi clock uncertainty across peers that have an estimate (phones only). */
    fun syncUncertaintyNanos(): Long? {
        val now = SystemClock.elapsedRealtimeNanos()
        return clocks.values.mapNotNull { clock -> clock.lastSampleTime()?.takeIf { now - it < 5_000_000_000L }?.let { clock.estimate(now)?.uncertaintyNanos } }.maxOrNull()
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) interfaces.nextElement().interfaceAddresses.mapNotNullTo(result) { it.broadcast }
        } catch (_: Exception) { }
        return result
    }

    private fun localSubnetAddresses(): Set<InetAddress> {
        val result = mutableSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) for (entry in interfaces.nextElement().interfaceAddresses) {
                val local = entry.address.address; val prefix = entry.networkPrefixLength.toInt()
                if (local.size != 4 || prefix !in 22..30 || entry.address.isLoopbackAddress) continue
                val address = ByteBuffer.wrap(local).int; val mask = -1 shl (32 - prefix); val network = address and mask; val hosts = (1 shl (32 - prefix)) - 1
                for (host in 1 until hosts) { val candidate = network or host; if (candidate != address) result.add(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(candidate).array())) }
            }
        } catch (_: Exception) { }
        return result
    }

    private inline fun safely(block: () -> Unit) { try { block() } catch (_: Exception) { } }

    companion object {
        const val ROLE_DISPLAY = 4
        private const val PORT = 48123
        private const val MAGIC: Byte = 0x53
        private const val VERSION: Byte = 2
        private const val HEADER = 11
        private const val SYNC_REQUEST = 1
        private const val SYNC_RESPONSE = 2
        private const val EVENT = 3
        private const val RESULT = 4
        private const val HELLO = 5
        private const val CONTROL = 6
        private const val STATUS = 7
        private const val TICK_MILLIS = 250L
        private const val SWEEP_EVERY_TICKS = 20
        private const val EXPECTED_PEERS = 3
        private const val LIVE_MILLIS = 6_000L
        private const val NODE_EXPIRY_MILLIS = 30_000L
    }
}
