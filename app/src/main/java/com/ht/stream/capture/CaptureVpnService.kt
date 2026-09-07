package com.ht.stream.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ht.stream.R
import com.ht.stream.data.CaptureMode
import com.ht.stream.data.FileLogger
import com.ht.stream.data.RequestStore
import com.ht.stream.proxy.LocalProxyServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 抓包 VPN 服务：建立本地 TUN，接管整机流量，
 * TCP 中继给 [LocalProxyServer] 解析，UDP（DNS）直接转发。
 */
class CaptureVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ht.stream.action.START"
        const val ACTION_STOP = "com.ht.stream.action.STOP"
        private const val TAG = "CaptureVpnService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "capture"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** 抓包开始时间（epoch ms），0 表示未在抓包 */
        private val _startedAt = MutableStateFlow(0L)
        val startedAt: StateFlow<Long> = _startedAt
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var active = false
    private var readerThread: Thread? = null

    private val writeLock = Any()
    private var tunOutput: FileOutputStream? = null

    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    private val proxyServer = LocalProxyServer()
    private val cleaner = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var packetCount = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, null -> {
                if (intent == null && !active) return START_NOT_STICKY
                if (intent?.action == ACTION_STOP) stopCapture()
            }
            ACTION_START -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (active) return
        startForegroundInternal()
        try {
            proxyServer.start(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed", e)
            stopSelf()
            return
        }

        val fd = Builder()
            .setSession("Packet capture")
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("223.5.5.5")
            .setBlocking(true)
            .apply {
                // 本 App 自身流量不经过 VPN，避免代理出站被环回
                try { addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
            .establish()

        if (fd == null) {
            Log.e(TAG, "establish failed")
            stopSelf()
            return
        }
        tun = fd
        tunOutput = FileOutputStream(fd.fileDescriptor)
        active = true
        _running.value = true
        _startedAt.value = System.currentTimeMillis()

        readerThread = Thread({ readLoop(fd) }, "tun-reader").apply { isDaemon = true }
        readerThread?.start()

        cleaner.scheduleWithFixedDelay({ cleanIdleSessions() }, 30, 30, TimeUnit.SECONDS)
        RequestStore.startSession()
        FileLogger.start(applicationContext)
        Log.i(TAG, "capture started")
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (active) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                if (active) Log.w(TAG, "tun read error: ${e.message}")
                break
            }
            if (n <= 0) continue
            try {
                dispatch(buf, n)
            } catch (e: Exception) {
                Log.w(TAG, "dispatch error: ${e.message}")
            }
        }
    }

    private fun dispatch(buf: ByteArray, n: Int) {
        packetCount++
        val ip = Packet.parseIp(buf, n)
        if (ip == null) {
            if (packetCount <= 20) Log.d(TAG, "non-ipv4/invalid packet len=$n")
            return
        }
        when (ip.protocol) {
            Packet.PROTO_TCP -> {
                val tcp = Packet.parseTcp(buf, ip) ?: return
                // 只关心 App → 外网 方向（源地址是 TUN 本机地址 10.0.0.2）
                if (!isTunAddress(ip.src)) {
                    if (tcp.flags and Packet.TCP_SYN != 0) {
                        Log.d(TAG, "TCP SYN skipped src=${Packet.ipKey(ip.src)} dst=${Packet.ipKey(ip.dst)}:${tcp.dstPort}")
                    }
                    return
                }
                if (tcp.flags and Packet.TCP_SYN != 0) {
                    Log.d(TAG, "TCP SYN ${Packet.ipKey(ip.src)}:${tcp.srcPort} -> ${Packet.ipKey(ip.dst)}:${tcp.dstPort}")
                }
                val key = "${Packet.ipKey(ip.src)}:${tcp.srcPort}->${Packet.ipKey(ip.dst)}:${tcp.dstPort}"
                val session = tcpSessions.getOrPut(key) {
                    TcpSession(
                        key = key,
                        appIp = ip.src, appPort = tcp.srcPort,
                        remoteIp = ip.dst, remotePort = tcp.dstPort,
                        proxyPort = proxyServer.port,
                        writeToTun = { writeToTun(it) },
                        onClose = { tcpSessions.remove(it) }
                    )
                }
                session.onAppSegment(ip, tcp, buf)
            }
            Packet.PROTO_UDP -> {
                val udp = Packet.parseUdp(buf, ip) ?: return
                if (!isTunAddress(ip.src)) return
                // QUIC 回退：丢弃到 443 的 UDP，App 会回退到 HTTPS(HTTP/2) 供 MITM 解密
                if (udp.dstPort == 443 && CaptureMode.quicBlockOn(applicationContext)) {
                    return
                }
                val key = "udp:${Packet.ipKey(ip.src)}:${udp.srcPort}->${Packet.ipKey(ip.dst)}:${udp.dstPort}"
                val session = udpSessions.getOrPut(key) {
                    UdpSession(
                        key = key,
                        appIp = ip.src, appPort = udp.srcPort,
                        remoteIp = ip.dst, remotePort = udp.dstPort,
                        writeToTun = { writeToTun(it) },
                        onClose = { udpSessions.remove(it) }
                    )
                }
                session.onAppDatagram(buf, udp)
            }
        }
    }

    private fun isTunAddress(ip: ByteArray): Boolean =
        ip.size == 4 && ip[0].toInt() == 10 && ip[1].toInt() == 0 && ip[2].toInt() == 0

    private fun writeToTun(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                tunOutput?.write(packet)
                tunOutput?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "tun write: ${e.message}")
            }
        }
    }

    private fun cleanIdleSessions() {
        val now = System.currentTimeMillis()
        tcpSessions.values.filter { now - it.lastActive > 180_000 }.forEach { it.close() }
        udpSessions.values.filter { now - it.lastActive > 90_000 }.forEach { it.close() }
    }

    private fun stopCapture() {
        active = false
        _running.value = false
        _startedAt.value = 0L
        tcpSessions.values.forEach { it.close() }
        udpSessions.values.forEach { it.close() }
        tcpSessions.clear(); udpSessions.clear()
        proxyServer.stop()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        tunOutput = null
        cleaner.shutdownNow()
        RequestStore.endSession()
        FileLogger.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "capture stopped")
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        val notif = builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }
}
