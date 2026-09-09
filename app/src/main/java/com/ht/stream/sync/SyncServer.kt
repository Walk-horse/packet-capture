package com.ht.stream.sync

import android.content.Context
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桌面同步 HTTP 服务：手机端做 server，Mac 面板轮询拉取抓包数据。
 *
 *  - GET /api/state?after=<lastExchangeId>   同步快照（增量 / 全量兜底）
 *  - GET /api/ping                           探活
 *  - GET /                                   服务自检
 *
 * 抓包开启时本机外发流量走 TUN，但服务接收的是局域网入站连接，不受 VPN 影响。
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
        return true
    }

    @Synchronized
    fun stop() {
        runningFlag.set(false)
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
                // 丢弃请求头（GET 无 body）
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
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
