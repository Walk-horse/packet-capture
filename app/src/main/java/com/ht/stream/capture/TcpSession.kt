package com.ht.stream.capture

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单条 TCP 连接的用户态协议栈（简化版）。
 * App 侧经 TUN 收发报文；真实流量中继到本机代理（LocalProxyServer），
 * 由代理解析 HTTP / 做 TLS MITM 后再访问真实目标。
 *
 * 简化假设：本机 VPN 环境几乎无丢包/乱序，故不重传、不缓存乱序段，
 * 只按序接收 + 及时 ACK。
 */
class TcpSession(
    val key: String,
    private val appIp: ByteArray,      // TUN 内 App 地址（如 10.0.0.2）
    private val appPort: Int,
    private val remoteIp: ByteArray,   // 原始目标
    private val remotePort: Int,
    private val proxyPort: Int,
    private val uid: Int,              // 发起连接的应用 uid（-1 未知）
    private val writeToTun: (ByteArray) -> Unit,
    private val onClose: (String) -> Unit
) {
    companion object {
        private const val TAG = "TcpSession"
        private const val MAX_PAYLOAD = 1400
        private const val PROXY_DEST_LINE = "DEST %s %d %d\n" // ip port uid
    }

    private val closed = AtomicBoolean(false)
    @Volatile private var appNext: Long = 0      // 期望从 App 收到的下一个 seq
    @Volatile private var proxyNext: Long = 0    // 发给 App 的下一个 seq
    @Volatile private var appFin = false
    @Volatile private var proxyEof = false
    @Volatile private var finSentToApp = false
    @Volatile var lastActive = System.currentTimeMillis()

    private var proxySocket: Socket? = null
    private var proxyOut: OutputStream? = null

    /** 处理来自 App（TUN 方向）的 TCP 段 */
    fun onAppSegment(ip: Packet.IpHeader, tcp: Packet.TcpSegment, packet: ByteArray) {
        if (closed.get()) return
        lastActive = System.currentTimeMillis()

        if (tcp.flags and Packet.TCP_RST != 0) { close(); return }

        if (tcp.flags and Packet.TCP_SYN != 0) {
            handleSyn(tcp)
            return
        }

        if (proxySocket == null) {
            // 未建连却收到数据，回 RST
            sendToApp(tcp, 0L, Packet.TCP_RST or Packet.TCP_ACK)
            return
        }

        if (tcp.payloadLen > 0) {
            when {
                tcp.seq == appNext -> {
                    try {
                        proxyOut?.write(packet, tcp.payloadOffset, tcp.payloadLen)
                        proxyOut?.flush()
                        appNext += tcp.payloadLen
                    } catch (e: IOException) {
                        close(); return
                    }
                }
                seqLess(tcp.seq + tcp.payloadLen, appNext) -> Unit // 完全重复，直接 ACK
                else -> Unit // 乱序段：丢弃，靠 App 重传（本机环境极少发生）
            }
            sendToApp(tcp, 0L, Packet.TCP_ACK)
        }

        if (tcp.flags and Packet.TCP_FIN != 0) {
            if (!appFin) {
                appFin = true
                appNext += 1
                sendToApp(tcp, 0L, Packet.TCP_ACK)
                try { proxySocket?.shutdownOutput() } catch (_: Exception) {}
                maybeSendFin()
            } else {
                sendToApp(tcp, 0L, Packet.TCP_ACK)
            }
        }

        // App 对我们 FIN 的 ACK → 收尾
        if (finSentToApp && tcp.flags and Packet.TCP_ACK != 0 && !seqLess(tcp.ack, proxyNext)) {
            close()
        }
    }

    private fun handleSyn(tcp: Packet.TcpSegment) {
        if (proxySocket != null) {
            // 重复 SYN（重传），重发 SYN-ACK
            sendSynAck(tcp)
            return
        }
        appNext = tcp.seq + 1
        proxyNext = Random().nextInt().toLong() and 0x7FFFFFFFL
        Log.d(TAG, "[$key] SYN seq=${tcp.seq}")

        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress("127.0.0.1", proxyPort), 5000)
            // 把原始目标告知代理
            val prelude = PROXY_DEST_LINE.format(Packet.ipToString(remoteIp), remotePort, uid)
                .toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().write(prelude)
            socket.getOutputStream().flush()
            proxySocket = socket
            proxyOut = socket.getOutputStream()
            sendSynAck(tcp)
            Log.d(TAG, "[$key] SYN-ACK sent, proxy connected")
            startProxyReader(socket.getInputStream())
        } catch (e: Exception) {
            Log.w(TAG, "[$key] connect proxy failed: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            close()
        }
    }

    private fun sendSynAck(tcp: Packet.TcpSegment) {
        val p = Packet.buildTcpPacket(
            srcIp = remoteIp, dstIp = appIp,
            srcPort = remotePort, dstPort = appPort,
            seq = proxyNext, ack = appNext,
            flags = Packet.TCP_SYN or Packet.TCP_ACK,
            withMssOption = true
        )
        proxyNext += 1 // SYN 占一个序号
        writeToTun(p)
    }

    /** 代理 → App 方向的数据转发 */
    private fun startProxyReader(input: InputStream) {
        Thread({
            val buf = ByteArray(8192)
            try {
                while (!closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var off = 0
                    while (off < n) {
                        val chunk = minOf(MAX_PAYLOAD, n - off)
                        val p = Packet.buildTcpPacket(
                            srcIp = remoteIp, dstIp = appIp,
                            srcPort = remotePort, dstPort = appPort,
                            seq = proxyNext, ack = appNext,
                            flags = Packet.TCP_PSH or Packet.TCP_ACK,
                            payload = buf, payloadOffset = off, payloadLen = chunk
                        )
                        proxyNext += chunk
                        writeToTun(p)
                        off += chunk
                    }
                    lastActive = System.currentTimeMillis()
                }
            } catch (_: Exception) {
            } finally {
                proxyEof = true
                maybeSendFin()
            }
        }, "proxy-reader-$key").apply { isDaemon = true }.start()
    }

    private fun maybeSendFin() {
        if (proxyEof && appFin && !finSentToApp && !closed.get()) {
            finSentToApp = true
            val p = Packet.buildTcpPacket(
                srcIp = remoteIp, dstIp = appIp,
                srcPort = remotePort, dstPort = appPort,
                seq = proxyNext, ack = appNext,
                flags = Packet.TCP_FIN or Packet.TCP_ACK
            )
            proxyNext += 1
            writeToTun(p)
            // App 若一直不 ACK，由空闲清理兜底
        }
    }

    private fun sendToApp(tcp: Packet.TcpSegment, seqDelta: Long, flags: Int) {
        val p = Packet.buildTcpPacket(
            srcIp = remoteIp, dstIp = appIp,
            srcPort = remotePort, dstPort = appPort,
            seq = proxyNext + seqDelta, ack = appNext,
            flags = flags
        )
        writeToTun(p)
    }

    private fun seqLess(a: Long, b: Long): Boolean = (a - b) < 0

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try { proxySocket?.close() } catch (_: Exception) {}
            onClose(key)
        }
    }
}
