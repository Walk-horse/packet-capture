package com.ht.stream.capture

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP 中继（主要用于 DNS）。每个 (App端口 -> 目标) 流一个 session，
 * 经 protect 的 DatagramSocket 直连目标，回包写回 TUN。
 */
class UdpSession(
    val key: String,
    private val appIp: ByteArray,
    private val appPort: Int,
    private val remoteIp: ByteArray,
    private val remotePort: Int,
    private val writeToTun: (ByteArray) -> Unit,
    private val onClose: (String) -> Unit
) {
    private val closed = AtomicBoolean(false)
    @Volatile var lastActive = System.currentTimeMillis()
    private var socket: DatagramSocket? = null

    @Synchronized
    private fun ensureSocket(): DatagramSocket? {
        socket?.let { return it }
        val s = DatagramSocket()
        s.soTimeout = 60_000
        s.connect(InetAddress.getByAddress(remoteIp), remotePort)
        socket = s
        startReader(s)
        return s
    }

    fun onAppDatagram(packet: ByteArray, udp: Packet.UdpDatagram) {
        if (closed.get()) return
        lastActive = System.currentTimeMillis()
        val s = ensureSocket() ?: return
        try {
            s.send(DatagramPacket(packet, udp.payloadOffset, udp.payloadLen))
        } catch (_: Exception) {
            close()
        }
    }

    private fun startReader(s: DatagramSocket) {
        Thread({
            val buf = ByteArray(65535)
            while (!closed.get()) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    s.receive(dp)
                    lastActive = System.currentTimeMillis()
                    val reply = Packet.buildUdpPacket(
                        srcIp = remoteIp, dstIp = appIp,
                        srcPort = remotePort, dstPort = appPort,
                        payload = dp.data, payloadOffset = dp.offset, payloadLen = dp.length
                    )
                    writeToTun(reply)
                } catch (_: Exception) {
                    break
                }
            }
            close()
        }, "udp-reader-$key").apply { isDaemon = true }.start()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try { socket?.close() } catch (_: Exception) {}
            onClose(key)
        }
    }
}
