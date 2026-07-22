package com.sprinttiming.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class TimingEvent(
    val senderId: Long,
    val role: Int,
    val localTimeNanos: Long,
    val gpsTimeNanos: Long?,
    val gpsUncertaintyNanos: Long,
    val wifiUncertaintyNanos: Long,
    val confidencePercent: Int
)

class PeerTiming(
    context: Context,
    private val displayOnly: Boolean,
    private val localRole: Int,
    private val onTimingEvent: (TimingEvent) -> Unit,
    private val onResultEvent: (Long, Long, Int, Long, Long, Boolean) -> Unit,
    private val onQuality: (Double, Double) -> Unit,
    private val onPeerSeen: () -> Unit,
    private val onControl: (Int, Int) -> Unit,
    private val onDisplaySeen: () -> Unit,
    private val onDeviceStatus: (Int, Boolean, Int, Boolean) -> Unit
) {
    private data class ClockEstimate(val offsets: ArrayDeque<Double> = ArrayDeque(), val delays: ArrayDeque<Double> = ArrayDeque())
    private data class PendingTimingEvent(val sender: Long, val role: Int, val localTime: Long, val gpsTime: Long, val gpsUncertainty: Long, val confidence: Int)

    private val multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("sprint-timing")?.apply { setReferenceCounted(false) }
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(3)
    private var socket: DatagramSocket? = null
    private val port = 48123
    private val nodeId = UUID.randomUUID().mostSignificantBits
    private val peers = ConcurrentHashMap.newKeySet<InetAddress>()
    private val clocks = ConcurrentHashMap<Long, ClockEstimate>()
    private val pendingEvents = ConcurrentHashMap<Long, PendingTimingEvent>()
    @Volatile var offsetNanos: Long? = null
        private set
    @Volatile var roundTripNanos: Long? = null
        private set
    private var lastResultHash = 0L
    @Volatile private var lastDiscoverySweepAt = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try { multicastLock?.acquire() } catch (_: Exception) { }
        executor.execute {
            try {
                val s = DatagramSocket(port).also { it.broadcast = true; it.soTimeout = 1000; socket = it }
                val buf = ByteArray(96)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        s.receive(packet)
                        receive(s, packet)
                    } catch (_: SocketTimeoutException) { }
                }
            } catch (_: Exception) { } finally { socket?.close() }
        }
        executor.execute {
            while (running.get()) {
                if (displayOnly) sendHello() else sendSync()
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun receive(s: DatagramSocket, packet: DatagramPacket) {
        val b = ByteBuffer.wrap(packet.data, 0, packet.length)
        if (b.remaining() < 2) return
        when (b.get().toInt()) {
            1 -> if (b.remaining() >= 16) {
                val sender = b.long
                val t1 = b.long
                if (sender == nodeId) return
                peers.add(packet.address); onPeerSeen()
                if (displayOnly) return
                val t2 = SystemClock.elapsedRealtimeNanos()
                val out = ByteBuffer.allocate(41).put(2).putLong(sender).putLong(nodeId).putLong(t1).putLong(t2).putLong(SystemClock.elapsedRealtimeNanos()).array()
                try { s.send(DatagramPacket(out, out.size, packet.address, port)) } catch (_: Exception) { }
            }
            2 -> if (b.remaining() >= 40) {
                val recipient = b.long
                val responder = b.long
                if (recipient != nodeId || responder == nodeId || displayOnly) return
                peers.add(packet.address); onPeerSeen()
                val t4 = SystemClock.elapsedRealtimeNanos()
                val t1 = b.long; val t2 = b.long; val t3 = b.long
                val delay = ((t4 - t1) - (t3 - t2)).toDouble()
                val offset = ((t2 - t1) + (t3 - t4)) / 2.0
                val estimate = clocks.getOrPut(responder) { ClockEstimate() }
                synchronized(estimate) {
                    estimate.offsets.add(offset); estimate.delays.add(delay)
                    while (estimate.offsets.size > 20) { estimate.offsets.removeFirst(); estimate.delays.removeFirst() }
                    val best = estimate.delays.indices.minByOrNull { estimate.delays.elementAt(it) } ?: 0
                    offsetNanos = estimate.offsets.elementAt(best).toLong()
                    roundTripNanos = estimate.delays.elementAt(best).toLong()
                    onQuality(offsetNanos!! / 1e6, roundTripNanos!! / 1e6)
                }
                pendingEvents.remove(responder)?.let { deliverTimingEvent(it) }
            }
            3 -> if (b.remaining() >= 37) {
                val sender = b.long
                val role = b.get().toInt()
                val remoteLocalTime = b.long
                val gpsValue = b.long
                val gpsUncertainty = b.long
                val confidence = b.int
                if (sender == nodeId) return
                peers.add(packet.address); onPeerSeen()
                val event = PendingTimingEvent(sender, role, remoteLocalTime, gpsValue, gpsUncertainty, confidence)
                if (!deliverTimingEvent(event)) {
                    pendingEvents[sender] = event
                    sendSyncTo(s, packet.address)
                }
            }
            4 -> if (b.remaining() >= 45) {
                val sender = b.long
                if (sender == nodeId) return
                peers.add(packet.address); onPeerSeen()
                val split = b.long; val total = b.long; val confidence = b.int
                val splitUncertainty = b.long; val totalUncertainty = b.long; val wifiFallback = b.get().toInt() != 0
                val hash = split xor total xor splitUncertainty xor totalUncertainty xor confidence.toLong()
                if (hash != lastResultHash) {
                    lastResultHash = hash
                    onResultEvent(split, total, confidence, splitUncertainty, totalUncertainty, wifiFallback)
                }
            }
            5 -> if (b.remaining() >= 9) {
                val sender = b.long
                if (sender != nodeId) {
                    val peerRole = b.get().toInt()
                    peers.add(packet.address); onPeerSeen()
                    if (peerRole == ROLE_DISPLAY && !displayOnly) onDisplaySeen()
                }
            }
            6 -> if (b.remaining() >= 14) {
                val sender = b.long; val targetRole = b.get().toInt(); val action = b.get().toInt(); val value = b.int
                if (sender != nodeId && targetRole == localRole && !displayOnly) {
                    peers.add(packet.address); onPeerSeen(); onControl(action, value)
                }
            }
            7 -> if (b.remaining() >= 15) {
                val sender = b.long
                if (sender != nodeId) {
                    val deviceRole = b.get().toInt(); val armed = b.get().toInt() != 0
                    val sensitivity = b.int; val preview = b.get().toInt() != 0
                    peers.add(packet.address); onPeerSeen(); onDeviceStatus(deviceRole, armed, sensitivity, preview)
                }
            }
        }
    }

    private fun bestClock(sender: Long): Pair<Long, Long>? {
        val estimate = clocks[sender] ?: return null
        synchronized(estimate) {
            if (estimate.delays.isEmpty()) return null
            val best = estimate.delays.indices.minByOrNull { estimate.delays.elementAt(it) } ?: return null
            return estimate.offsets.elementAt(best).toLong() to estimate.delays.elementAt(best).toLong()
        }
    }

    private fun deliverTimingEvent(event: PendingTimingEvent): Boolean {
        val estimate = bestClock(event.sender) ?: return false
        onTimingEvent(TimingEvent(
            senderId = event.sender,
            role = event.role,
            localTimeNanos = event.localTime - estimate.first,
            gpsTimeNanos = event.gpsTime.takeIf { it != 0L },
            gpsUncertaintyNanos = event.gpsUncertainty,
            wifiUncertaintyNanos = (estimate.second / 2).coerceAtLeast(0L),
            confidencePercent = event.confidence
        ))
        return true
    }

    private fun sendSync() { val t = SystemClock.elapsedRealtimeNanos(); send(ByteBuffer.allocate(17).put(1).putLong(nodeId).putLong(t).array()) }
    private fun sendSyncTo(s: DatagramSocket, address: InetAddress) {
        val t = SystemClock.elapsedRealtimeNanos()
        val data = ByteBuffer.allocate(17).put(1).putLong(nodeId).putLong(t).array()
        try { s.send(DatagramPacket(data, data.size, address, port)) } catch (_: Exception) { }
    }
    private fun sendHello() = send(ByteBuffer.allocate(10).put(5).putLong(nodeId).put(localRole.toByte()).array())
    fun broadcastTimingEvent(role: Int, localTime: Long, gpsTime: Long?, uncertainty: Long, confidence: Int) = repeatSend(
        ByteBuffer.allocate(38).put(3).putLong(nodeId).put(role.toByte()).putLong(localTime).putLong(gpsTime ?: 0L).putLong(uncertainty).putInt(confidence).array(),
        count = 16,
        gapMillis = 250,
        forceSubnet = true
    )
    fun broadcastResult(split: Long, total: Long, confidence: Int, splitUncertainty: Long, totalUncertainty: Long, wifiFallback: Boolean) = repeatSend(
        ByteBuffer.allocate(46).put(4).putLong(nodeId).putLong(split).putLong(total).putInt(confidence).putLong(splitUncertainty).putLong(totalUncertainty).put(if (wifiFallback) 1 else 0).array()
    )
    fun broadcastControl(targetRole: Int, action: Int, value: Int) = repeatSend(ByteBuffer.allocate(15).put(6).putLong(nodeId).put(targetRole.toByte()).put(action.toByte()).putInt(value).array())
    fun broadcastStatus(deviceRole: Int, armed: Boolean, sensitivity: Int, preview: Boolean) = repeatSend(ByteBuffer.allocate(16).put(7).putLong(nodeId).put(deviceRole.toByte()).put(if (armed) 1 else 0).putInt(sensitivity).put(if (preview) 1 else 0).array())
    private fun repeatSend(data: ByteArray, count: Int = 3, gapMillis: Long = 80, forceSubnet: Boolean = false) { executor.execute { repeat(count) { index -> send(data, forceSubnet && index < 3); try { Thread.sleep(gapMillis) } catch (_: InterruptedException) { return@execute } } } }

    private fun send(data: ByteArray, forceSubnet: Boolean = false) {
        try {
            val active = socket ?: return
            val targets = mutableSetOf<InetAddress>().apply { addAll(broadcastAddresses()); addAll(peers) }
            val now = SystemClock.elapsedRealtime()
            if (forceSubnet || now - lastDiscoverySweepAt >= 3_000) { lastDiscoverySweepAt = now; targets.addAll(localSubnetAddresses()) }
            targets.forEach { target -> try { active.send(DatagramPacket(data, data.size, target, port)) } catch (_: Exception) { } }
        } catch (_: Exception) { }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val result = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        try { val interfaces = NetworkInterface.getNetworkInterfaces(); while (interfaces.hasMoreElements()) interfaces.nextElement().interfaceAddresses.mapNotNullTo(result) { it.broadcast } } catch (_: Exception) { }
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

    fun stop() { running.set(false); socket?.close(); executor.shutdownNow(); try { if (multicastLock?.isHeld == true) multicastLock.release() } catch (_: Exception) { } }

    companion object { const val ROLE_DISPLAY = 4 }
}
