package com.ht.stream.sync

import android.content.Context
import com.ht.stream.data.RequestStore
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 桌面同步服务：手机端做 server，Mac 面板拉取 / 接收抓包数据。
 *
 *  - GET /api/state?after=<lastExchangeId>   同步快照（增量 / 全量兜底） —— 原轮询逻辑，保持不变
 *  - GET /api/ping                           探活
 *  - GET /                                   服务自检
 *  - GET /api/ws  (Upgrade: websocket)       移动端主动推送：连接时下发全量快照，之后数据变化即时增量推送
 *
 * 抓包开启时本机外发流量走 TUN，但服务接收的是局域网/USB 入站连接，不受 VPN 影响。
 */
object SyncServer {

    const val DEFAULT_PORT = 17890

    private val runningFlag = AtomicBoolean(false)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile var port: Int = DEFAULT_PORT
        private set

    /** 最近一次错误（端口占用等） */
    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = runningFlag.get()

    /** 实际对外地址，UI 展示给用户在 Mac 端填写 */
    val address: String get() = "${localIpv4() ?: "手机IP"}:$port"

    // ---------- WebSocket 推送 ----------
    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var broadcastJob: Job? = null
    private val wsClients = Collections.synchronizedList(mutableListOf<WsClient>())

    @Synchronized
    fun start(preferred: Int = DEFAULT_PORT): Boolean {
        if (runningFlag.get()) return true
        lastError = null
        var ss: ServerSocket? = null
        var usedPort = 0
        for (p in preferred until preferred + 10) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                ss = s
                usedPort = p
                break
            } catch (e: Exception) {
                // 端口被占用，尝试下一个
            }
        }
        val socket = ss ?: run {
            lastError = "端口 ${preferred}~${preferred + 9} 均被占用"
            return false
        }
        port = usedPort
        serverSocket = socket
        pool = Executors.newCachedThreadPool()
        runningFlag.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "sync-accept").apply {
            isDaemon = true
            start()
        }
        // 启动推送广播：监听 RequestStore 变化，向所有 WS 客户端增量推送
        if (broadcastJob == null) {
            broadcastJob = wsScope.launch {
                try {
                    RequestStore.tick.collect { broadcastDelta() }
                } catch (_: Exception) {
                    // scope 取消时结束
                }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        runningFlag.set(false)
        broadcastJob?.cancel()
        broadcastJob = null
        synchronized(wsClients) {
            wsClients.forEach { it.close() }
            wsClients.clear()
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (runningFlag.get()) {
            try {
                val client = ss.accept()
                pool?.execute { handle(client) }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 10_000
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]

                // 收集请求头（用于判断 WebSocket Upgrade）
                val headers = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    headers.add(line)
                }

                val isWs = headers.any { it.equals("upgrade: websocket", ignoreCase = true) }
                if (isWs) {
                    val key = headers.firstNotNullOfOrNull { h ->
                        val i = h.indexOf(':')
                        if (i > 0 && h.substring(0, i).equals("Sec-WebSocket-Key", ignoreCase = true))
                            h.substring(i + 1).trim() else null
                    }
                    if (key != null) {
                        handleWs(s, key)
                        return
                    }
                }

                val (path, query) = splitPath(rawPath)
                val body: ByteArray
                val code: Int
                when {
                    method != "GET" -> {
                        body = "{\"error\":\"only GET supported\"}".toByteArray(Charsets.UTF_8)
                        code = 405
                    }
                    path == "/api/state" -> {
                        val after = query["after"]?.takeIf { it.isNotBlank() }
                        body = SyncJson.state(after).toString().toByteArray(Charsets.UTF_8)
                        code = 200
                    }
                    path == "/api/ping" -> {
                        body = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
                        code = 200
                    }
                    path == "/" -> {
                        body = "{\"name\":\"stream-sync\",\"api\":\"/api/state\"}".toByteArray(Charsets.UTF_8)
                        code = 200
                    }
                    else -> {
                        body = "{\"error\":\"not found\"}".toByteArray(Charsets.UTF_8)
                        code = 404
                    }
                }

                val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(body)
                out.flush()
            }
        } catch (e: Exception) {
            // 客户端断连等，忽略
        }
    }

    // ================= WebSocket =================

    private fun handleWs(sock: Socket, key: String) {
        sock.soTimeout = 0 // 推送通道允许空闲，不设读超时（断连由对方 FIN 触发）
        val accept = computeAcceptKey(key)
        val handshake = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(accept).append("\r\n\r\n")
        }
        val out = sock.getOutputStream()
        out.write(handshake.toByteArray(Charsets.UTF_8))
        out.flush()

        val client = WsClient(sock, out)
        // 下发全量快照
        runCatching {
            client.lastEx = RequestStore.exchanges.value.firstOrNull()?.id
            client.lastPass = RequestStore.passthrough.value.firstOrNull()?.id
            client.send(SyncJson.wsSnapshot().toString())
        }
        wsClients.add(client)

        try {
            // 读取客户端帧：仅处理 close / ping，检测断连
            while (client.alive) {
                val frame = readClientFrame(sock) ?: break
                when (frame.opcode) {
                    0x8 -> break                 // close
                    0x9 -> client.send(wsFrame(frame.payload, 0xA)) // pong
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // 读异常 → 视为断开
        } finally {
            wsClients.remove(client)
            client.close()
        }
    }

    /** 向所有 WS 客户端增量推送自上次以来的新增请求 / 透传 + 最新统计 */
    private fun broadcastDelta() {
        val topEx = RequestStore.exchanges.value.firstOrNull()?.id
        val topPass = RequestStore.passthrough.value.firstOrNull()?.id
        val snapshot = synchronized(wsClients) { wsClients.toList() }
        for (client in snapshot) {
            if (!client.alive) continue
            val json = runCatching { SyncJson.wsDelta(client.lastEx, client.lastPass).toString() }.getOrNull() ?: continue
            client.send(json)
            if (client.alive) {
                client.lastEx = topEx
                client.lastPass = topPass
            }
        }
    }

    private class WsClient(val socket: Socket, private val out: OutputStream) {
        @Volatile var alive = true
        var lastEx: String? = null
        var lastPass: String? = null
        private val lock = Any()

        fun send(text: String) {
            synchronized(lock) {
                if (!alive) return
                try {
                    out.write(wsFrame(text.toByteArray(Charsets.UTF_8), 0x1))
                    out.flush()
                } catch (_: Exception) {
                    alive = false
                }
            }
        }

        fun send(frame: ByteArray) {
            synchronized(lock) {
                if (!alive) return
                try {
                    out.write(frame)
                    out.flush()
                } catch (_: Exception) {
                    alive = false
                }
            }
        }

        fun close() {
            alive = false
            runCatching { socket.close() }
        }
    }

    /** 构造服务端→客户端 未掩码 文本/控制帧 */
    private fun wsFrame(payload: ByteArray, opcode: Int): ByteArray {
        val len = payload.size
        val out = java.io.ByteArrayOutputStream()
        out.write(0x80 or opcode) // FIN + opcode
        when {
            len < 126 -> out.write(len)
            126 <= len && len < 65536 -> {
                out.write(126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(127)
                var l = len.toLong()
                for (i in 7 downTo 0) {
                    out.write(((l shr (8 * i)) and 0xFF).toInt())
                }
            }
        }
        out.write(payload)
        return out.toByteArray()
    }

    /** 读取客户端（已掩码）单帧，返回 (opcode, payload) */
    private fun readClientFrame(sock: Socket): WsFrame? {
        val `in` = sock.getInputStream()
        val b0 = `in`.read(); if (b0 < 0) return null
        val b1 = `in`.read(); if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = b1 and 0x7F
        if (len == 126) len = readN(`in`, 2)
        else if (len == 127) {
            // 控制帧不会到这；超大长度直接当作断开
            readN(`in`, 8); return null
        }
        val maskKey = if (masked) readExact(`in`, 4) else null
        val payload = if (len > 0) readExact(`in`, len) else ByteArray(0)
        if (masked && maskKey != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        return WsFrame(opcode, payload)
    }

    private fun readN(`in`: java.io.InputStream, n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 8) or (`in`.read().takeIf { it >= 0 } ?: return -1) }
        return v
    }

    private fun readExact(`in`: java.io.InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = `in`.read(buf, off, n - off)
            if (r < 0) return buf.copyOf(off)
            off += r
        }
        return buf
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun computeAcceptKey(key: String): String {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha.digest())
    }

    // =============================================

    private fun splitPath(raw: String): Pair<String, Map<String, String>> {
        val qIndex = raw.indexOf('?')
        val path = if (qIndex < 0) raw else raw.substring(0, qIndex)
        val map = mutableMapOf<String, String>()
        if (qIndex >= 0) {
            raw.substring(qIndex + 1).split("&").forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) map[kv.substring(0, i)] = kv.substring(i + 1)
            }
        }
        return path to map
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }
}

/** 当前局域网 IPv4（优先 wlan 接口），供 Mac 端填写地址 */
fun localIpv4(): String? {
    return try {
        val candidates = mutableListOf<String>()
        val nis = NetworkInterface.getNetworkInterfaces() ?: return null
        while (nis.hasMoreElements()) {
            val ni = nis.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name.lowercase()
            ni.inetAddresses.toList().forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    val ip = addr.hostAddress ?: return@forEach
                    if (name.startsWith("wlan") || name.startsWith("eth")) return ip
                    candidates.add(ip)
                }
            }
        }
        candidates.firstOrNull()
    } catch (e: Exception) {
        null
    }
}

/** 同步开关持久化（跟随 App 进程，重启后需重新打开） */
object SyncPrefs {
    private const val P = "sync_prefs"
    private const val KEY_ON = "sync_on"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ON, on).apply()
}
