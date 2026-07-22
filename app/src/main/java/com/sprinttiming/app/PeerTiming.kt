package com.sprinttiming.app

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class PeerTiming(
    private val displayOnly: Boolean,
    private val onStartEvent: (Long, Long, Boolean) -> Unit,
    private val onResultEvent: (Long, Int, Long) -> Unit,
    private val onQuality: (Double, Double) -> Unit,
    private val onPeerSeen: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(3)
    private var socket: DatagramSocket? = null
    private val offsets = ArrayDeque<Double>()
    private val delays = ArrayDeque<Double>()
    private val port = 48123
    private val nodeId = UUID.randomUUID().mostSignificantBits
    private val peers = ConcurrentHashMap.newKeySet<InetAddress>()
    @Volatile var offsetNanos: Long? = null
        private set
    @Volatile var roundTripNanos: Long? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val s = DatagramSocket(port).also { it.broadcast = true; it.soTimeout = 1000; socket = it }
                val buf = ByteArray(64)
                while (running.get()) {
                    try {
                        val p = DatagramPacket(buf, buf.size); s.receive(p); receive(s, p)
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

    private fun receive(s: DatagramSocket, p: DatagramPacket) {
        val b = ByteBuffer.wrap(p.data, 0, p.length); if (b.remaining() < 2) return
        when (b.get().toInt()) {
            1 -> if (b.remaining() >= 16) { val sender = b.long; val t1 = b.long; if (sender == nodeId) return; peers.add(p.address); onPeerSeen(); if (displayOnly) return; val t2 = SystemClock.elapsedRealtimeNanos(); val out = ByteBuffer.allocate(41).put(2).putLong(sender).putLong(nodeId).putLong(t1).putLong(t2).putLong(SystemClock.elapsedRealtimeNanos()).array(); s.send(DatagramPacket(out, out.size, p.address, port)) }
            2 -> if (b.remaining() >= 40) { val recipient = b.long; val responder = b.long; if (recipient != nodeId || responder == nodeId || displayOnly) return; onPeerSeen(); val t4 = SystemClock.elapsedRealtimeNanos(); val t1 = b.long; val t2 = b.long; val t3 = b.long; val delay = (t4 - t1) - (t3 - t2); val offset = ((t2 - t1) + (t3 - t4)) / 2.0; synchronized(offsets) { offsets.add(offset); delays.add(delay.toDouble()); while (offsets.size > 20) { offsets.removeFirst(); delays.removeFirst() }; val best = delays.indices.minByOrNull { delays.elementAt(it) } ?: 0; offsetNanos = offsets.elementAt(best).toLong(); roundTripNanos = delays.elementAt(best).toLong(); onQuality(offsets.elementAt(best) / 1e6, delays.elementAt(best) / 1e6) } }
            3 -> if (b.remaining() >= 25) { val sender = b.long; if (sender != nodeId) { peers.add(p.address); onPeerSeen(); val wifiFallback = b.get().toInt() != 0; onStartEvent(b.long, b.long, wifiFallback) } }
            4 -> if (b.remaining() >= 28) { val sender = b.long; if (sender != nodeId) { peers.add(p.address); onPeerSeen(); onResultEvent(b.long, b.int, b.long) } }
            5 -> if (b.remaining() >= 8) { val sender = b.long; if (sender != nodeId) { peers.add(p.address); onPeerSeen() } }
        }
    }

    private fun sendSync() { val t = SystemClock.elapsedRealtimeNanos(); send(ByteBuffer.allocate(17).put(1).putLong(nodeId).putLong(t).array()) }
    private fun sendHello() = send(ByteBuffer.allocate(9).put(5).putLong(nodeId).array())
    fun broadcastStart(eventNanos: Long, uncertaintyNanos: Long, wifiFallback: Boolean) = repeatSend(ByteBuffer.allocate(26).put(3).putLong(nodeId).put(if (wifiFallback) 1 else 0).putLong(eventNanos).putLong(uncertaintyNanos).array())
    fun broadcastResult(durationNanos: Long, confidencePercent: Int, uncertaintyNanos: Long) = repeatSend(ByteBuffer.allocate(29).put(4).putLong(nodeId).putLong(durationNanos).putInt(confidencePercent).putLong(uncertaintyNanos).array())
    private fun repeatSend(data: ByteArray) { executor.execute { repeat(3) { send(data); try { Thread.sleep(80) } catch (_: InterruptedException) { return@execute } } } }
    private fun send(data: ByteArray) { try { val active = socket ?: return; active.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), port)); peers.forEach { active.send(DatagramPacket(data, data.size, it, port)) } } catch (_: Exception) { } }
    fun stop() { running.set(false); socket?.close(); executor.shutdownNow() }
}
