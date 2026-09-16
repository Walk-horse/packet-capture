package com.ht.stream.sync

import android.content.Context
import com.ht.stream.data.MockStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 桌面同步服务：手机端做 server，Mac 面板拉取 / 接收抓包数据。
 *
 *  - GET /api/state?full=1   全量同步：返回全部请求（置已同步），客户端整体替换
 *  - GET /api/state          增量同步：仅返回尚未同步(synced=false)的请求（下发后置已同步）
 *  - GET /api/ping           探活
 *  - GET /api/mock           读取接口模拟配置（规则 + 按应用开关状态）
 *  - POST /api/mock          下发接口模拟规则（整体覆盖），body 为 {"rules":[...]} 或裸数组
 *  - POST /api/mock/apps     切换总开关 / 某应用开关，body {"master":bool,"enabled":[pkg,...]}
 *  - POST /api/mock/clear    清除本机接口模拟配置（规则 + 应用开关 + 总开关）
 *  - GET /                   服务自检
 *  - GET /api/ws  (Upgrade: websocket)       移动端主动推送：连接时下发未同步快照，之后数据变化即时增量推送
 *
 * 抓包开启时本机外发流量走 TUN；同步服务仅监听 127.0.0.1（loopback），流量经 adb forward 入站，
 * 不受 VPN 影响、也不暴露到局域网（消除同 WiFi 未授权访问风险）。
 */
object SyncServer {

    const val DEFAULT_PORT = 17890

    private val runningFlag = AtomicBoolean(false)

    /** 应用上下文（接口模拟配置读写需要）；由 MainActivity 启动时注入 */
    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile var port: Int = DEFAULT_PORT
        private set

    /** 最近一次错误（端口占用等） */
    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = runningFlag.get()

    /** 实际对外地址：仅 loopback（同步流量走 adb forward），UI 展示给用户经 USB 连接时填写 */
    val address: String get() = "127.0.0.1:$port"

    // ---------- WebSocket 推送 ----------
    private val wsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var broadcastJob: Job? = null
    @Volatile private var pingJob: Job? = null
    private val wsClients = Collections.synchronizedList(mutableListOf<WsClient>())

    /** WS 并发上限：防止重连风暴 / 多开面板在手机端无限制新建阻塞读线程与 FD，
     *  进而推高 adbd 侧 forward 子通道数、累积资源拖垮 adbd。 */
    private const val MAX_WS_CLIENTS = 5

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
                // 仅绑定 loopback：同步流量一律经 adb forward 入站，彻底消除「同 WiFi 任意设备可读全部明文流量」的未授权访问漏洞
                s.bind(InetSocketAddress("127.0.0.1", p))
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
        // 心跳探测：WS 连接设了 soTimeout=0（允许空闲），但静默掉线（合盖/切 WiFi 未发 FIN）
        // 会让服务端 read 线程永久阻塞、占住线程与 FD；定期发 ping，发送失败即判定对端已死并回收。
        if (pingJob == null) {
            pingJob = wsScope.launch {
                try {
                    while (true) {
                        delay(30_000)
                        val snapshot = synchronized(wsClients) { wsClients.toList() }
                        for (client in snapshot) {
                            if (!client.alive) continue
                            // 发送空 ping；对端已死时底层 write 抛异常 → alive=false
                            client.send(wsFrame(ByteArray(0), 0x9))
                            if (!client.alive) client.close() // 关闭 socket 使阻塞的 read 线程退出
                        }
                    }
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
        pingJob?.cancel()
        pingJob = null
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
                val input = s.getInputStream()
                val head = readHead(input) ?: return
                val parts = head.startLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val headers = head.headers

                // WebSocket 升级（推送通道）
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

                val reqBody = readBody(input, headers)
                val (path, query) = splitPath(rawPath)
                val ctx = appContext

                val body: ByteArray
                val code: Int
                when {
                    // 接口模拟：读取配置（规则 + 按应用开关）
                    path == "/api/mock" && method == "GET" -> {
                        body = mockConfigJson(ctx).toString().toByteArray(Charsets.UTF_8)
                        code = 200
                    }
                    // 接口模拟：下发规则（整体覆盖）
                    path == "/api/mock" && method == "POST" -> {
                        val result = applyMockRules(ctx, reqBody)
                        body = result.toString().toByteArray(Charsets.UTF_8)
                        code = if (result.optBoolean("ok")) 200 else 400
                    }
                    // 接口模拟：桌面端也可切某应用的开关
                    path == "/api/mock/apps" && method == "POST" -> {
                        val result = applyMockApps(ctx, reqBody)
                        body = result.toString().toByteArray(Charsets.UTF_8)
                        code = if (result.optBoolean("ok")) 200 else 400
                    }
                    // 接口模拟：清除本机配置（规则 + 应用开关 + 总开关）
                    path == "/api/mock/clear" && method == "POST" -> {
                        val result = if (ctx == null) {
                            JSONObject().put("ok", false).put("error", "app context 未注入")
                        } else {
                            MockStore.clearAll(ctx)
                        }
                        body = result.toString().toByteArray(Charsets.UTF_8)
                        code = if (result.optBoolean("ok")) 200 else 400
                    }
                    method != "GET" -> {
                        body = "{\"error\":\"unsupported method\"}".toByteArray(Charsets.UTF_8)
                        code = 405
                    }
                    path == "/api/state" -> {
                        val full = query["full"] == "1"
                        val json = SyncJson.state(full)
                        ctx?.let { json.put("mock", MockStore.status(it)) }
                        body = json.toString().toByteArray(Charsets.UTF_8)
                        code = 200
                    }
                    path == "/api/ping" -> {
                        body = "{\"ok\":true}".toByteArray(Charsets.UTF_8)
                        code = 200
                    }
                    path == "/" -> {
                        body = "{\"name\":\"stream-sync\",\"api\":\"/api/state\",\"mock\":\"/api/mock\",\"mockClear\":\"/api/mock/clear\"}".toByteArray(Charsets.UTF_8)
                        code = 200
                    }
                    else -> {
                        body = "{\"error\":\"not found\"}".toByteArray(Charsets.UTF_8)
                        code = 404
                    }
                }

                val headOut = "HTTP/1.1 $code ${reason(code)}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(headOut.toByteArray(Charsets.UTF_8))
                out.write(body)
                out.flush()
            }
        } catch (e: Exception) {
            // 客户端断连等，忽略
        }
    }

    // ================= 接口模拟配置 =================

    /** GET /api/mock：规则 + 各应用开关状态 */
    private fun mockConfigJson(ctx: Context?): JSONObject {
        if (ctx == null) return JSONObject().put("ok", false).put("error", "app context 未注入")
        return JSONObject().apply {
            put("ok", true)
            put("enabled", MockStore.isEnabled(ctx))
            put("rules", MockStore.rulesToJson(ctx))
            put("enabledApps", JSONArray().apply { MockStore.enabledApps(ctx).forEach { put(it) } })
            put("updatedAt", MockStore.rulesUpdatedAt(ctx))
        }
    }

    /** POST /api/mock：body 可为 {"rules":[...]} 或裸数组，整体覆盖规则 */
    private fun applyMockRules(ctx: Context?, payload: ByteArray): JSONObject {
        if (ctx == null) return JSONObject().put("ok", false).put("error", "app context 未注入")
        val text = String(payload, Charsets.UTF_8).trim()
        if (text.isEmpty()) return JSONObject().put("ok", false).put("error", "请求体为空")
        val array = runCatching {
            when (val v = JSONTokener(text).nextValue()) {
                is JSONArray -> v
                is JSONObject -> v.optJSONArray("rules") ?: JSONArray()
                else -> JSONArray()
            }
        }.getOrNull() ?: return JSONObject().put("ok", false).put("error", "JSON 解析失败")
        val count = MockStore.setRulesJson(ctx, array.toString())
        return JSONObject().apply {
            put("ok", true)
            put("count", count)
            put("enabled", MockStore.isEnabled(ctx))
        }
    }

    /** POST /api/mock/apps：body {"enabled":[pkg,...]} 或 {"enabled":true/false}（总开关） */
    private fun applyMockApps(ctx: Context?, payload: ByteArray): JSONObject {
        if (ctx == null) return JSONObject().put("ok", false).put("error", "app context 未注入")
        val text = String(payload, Charsets.UTF_8).trim()
        val obj = runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "JSON 解析失败")
        if (obj.has("master")) MockStore.setEnabled(ctx, obj.optBoolean("master"))
        obj.optJSONArray("enabled")?.let { arr ->
            val pkgs = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
            val current = MockStore.enabledApps(ctx)
            (current - pkgs).forEach { MockStore.setAppEnabled(ctx, it, false) }
            pkgs.forEach { MockStore.setAppEnabled(ctx, it, true) }
        }
        return JSONObject().apply {
            put("ok", true)
            put("enabled", MockStore.isEnabled(ctx))
            put("enabledApps", JSONArray().apply { MockStore.enabledApps(ctx).forEach { put(it) } })
        }
    }

    // ================= 报文读取 =================

    private class Head(val startLine: String, val headers: List<String>)

    /** 逐字节读取请求头（不用 BufferedReader，避免把 body 预读进缓冲区） */
    private fun readHead(input: java.io.InputStream): Head? {
        val startLine = readLine(input) ?: return null
        if (startLine.isEmpty()) return null
        val headers = mutableListOf<String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers.add(line)
            if (headers.size > 200) return null
        }
        return Head(startLine, headers)
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    /** 按 Content-Length 读取请求体（上限 8MB） */
    private fun readBody(input: java.io.InputStream, headers: List<String>): ByteArray {
        val len = headers.firstNotNullOfOrNull { h ->
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("Content-Length", true))
                h.substring(i + 1).trim().toLongOrNull() else null
        } ?: 0L
        if (len <= 0) return ByteArray(0)
        val n = len.coerceAtMost(8L * 1024 * 1024).toInt()
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    // ================= WebSocket =================

    private fun handleWs(sock: Socket, key: String) {
        sock.soTimeout = 0 // 推送通道允许空闲，不设读超时（断连由对方 FIN / 心跳 ping 触发）
        runCatching { sock.keepAlive = true } // 兜底：TCP 层探测死连接
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

        // 并发上限检查：超限直接关闭，避免僵尸线程/FD 累积拖垮手机端乃至 adbd
        if (synchronized(wsClients) { wsClients.count { it.alive } } >= MAX_WS_CLIENTS) {
            runCatching { sock.close() }
            return
        }

        val client = WsClient(sock, out)
        // 下发增量快照（仅尚未同步的「已落定」记录，并置已同步）
        runCatching {
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

    /** 向所有 WS 客户端增量推送自上次以来的新增「已落定且未同步」请求 + 最新统计 */
    private fun broadcastDelta() {
        // 关键：没有存活的 WS 客户端时直接返回，绝不能取数据。
        // takeExchangesForSync(false) 会把未同步记录置为 synced=true，
        // 而桌面端可能只走 HTTP 轮询（如 Windows 版从不连 WS）——
        // 若在此处取走数据却没有客户端接收，新请求会被标记已同步后凭空丢弃，
        // HTTP 增量轮询从此永远拉不到新数据（表现为桌面端清屏后不再同步）。
        if (wsClients.none { it.alive }) return
        // 与 HTTP 增量共用 synced 标志位：取 settled && !synced 并置位，桌面端按 id 去重
        // takeExchangesForSync 默认按批上限（200）取，未同步过多时自动分批并触发后续广播，避免单条 JSON 过大 OOM
        val send = RequestStore.takeExchangesForSync(false)
        if (send.isEmpty()) return
        val json = runCatching { SyncJson.wsDelta(send).toString() }.getOrNull() ?: return
        val snapshot = synchronized(wsClients) { wsClients.toList() }
        for (client in snapshot) {
            if (client.alive) client.send(json)
        }
    }

    /** 抓包状态变更（开始 / 停止）立即广播：桌面端 WS 收到后即时刷新「正在抓包」指示，
     *  不依赖桌面端轮询（autoSync 关闭也不受影响）。
     *  无存活 WS 客户端时直接返回：本推送只发状态、不取请求数据，因此即便返回也不影响 synced 标志位。 */
    fun broadcastStatus() {
        if (wsClients.none { it.alive }) return
        val json = runCatching { SyncJson.wsStatus().toString() }.getOrNull() ?: return
        val snapshot = synchronized(wsClients) { wsClients.toList() }
        for (client in snapshot) {
            if (client.alive) client.send(json)
        }
    }

    private class WsClient(val socket: Socket, private val out: OutputStream) {
        @Volatile var alive = true
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
