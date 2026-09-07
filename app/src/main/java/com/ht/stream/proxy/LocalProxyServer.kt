package com.ht.stream.proxy

import android.content.Context
import android.util.Log
import com.ht.stream.data.CaptureMode
import com.ht.stream.data.FileLogger
import com.ht.stream.data.HostsStore
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.PassthroughRec
import com.ht.stream.data.RequestStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * 本机回环代理（127.0.0.1:8888）。
 * TUN 中继把每条 TCP 连接交给它，首行为 "DEST <ip> <port>\n" 告知原始目标。
 * 按流量特征分流：
 *  - TLS ClientHello → 解析 SNI → 动态签证书做 MITM → 按 HTTP 解析
 *  - 明文 HTTP → 解析并记录
 *  - 其他 → 盲转发
 * 每条 App 连接只处理一个请求（对 App 回 Connection: close），简化实现。
 */
class LocalProxyServer {
    companion object {
        private const val TAG = "LocalProxyServer"
        private val HTTP_METHODS = listOf(
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT", "TRACE"
        )

        /** 握手失败过的 host：之后直连盲转发（不信任本 CA 的 App 不再被打断） */
        private val mitmBlacklist = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** 清除 MITM 缓存：站点证书缓存 + 失败黑名单 */
        fun clearMitmCache() {
            mitmBlacklist.clear()
            CertAuthority.clearCache()
        }
    }

    val port = 8888
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private lateinit var appContext: Context

    /** 应用 Hosts 映射，返回实际要连接的目标地址 */
    private fun upstreamHost(host: String, fallbackIp: String): String =
        HostsStore.resolve(appContext, host) ?: fallbackIp

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", port), 128)
        serverSocket = ss
        running = true
        pool.execute {
            while (running) {
                try {
                    val client = ss.accept()
                    pool.execute {
                        runCatching { handle(client) }.onFailure {
                            Log.w(TAG, "conn error: ${it.message}")
                            runCatching { client.close() }
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept: ${e.message}")
                }
            }
        }
        Log.i(TAG, "proxy listening on 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    private fun handle(app: Socket) {
        app.soTimeout = 30_000
        app.tcpNoDelay = true
        val rawIn = app.getInputStream() // 未缓冲，保证嗅探不丢字节
        val appOut = BufferedOutputStream(app.getOutputStream(), 64 * 1024)

        // 1. 读取 DEST 前导行（DEST <ip> <port> [uid]）
        val destLine = readLineRaw(rawIn) ?: return
        if (!destLine.startsWith("DEST ")) return
        val parts = destLine.split(" ")
        val destIp = parts.getOrNull(1) ?: return
        val destPort = parts.getOrNull(2)?.toIntOrNull() ?: return
        val uid = parts.getOrNull(3)?.toIntOrNull() ?: -1

        // 2. 嗅探首字节分流
        val first = rawIn.read()
        if (first < 0) { app.close(); return }

        if (first == 0x16) {
            // TLS：读完整 ClientHello 记录 → SNI
            val headerRest = ByteArray(4)
            readFully(rawIn, headerRest)
            val recordLen = ((headerRest[2].toInt() and 0xFF) shl 8) or (headerRest[3].toInt() and 0xFF)
            if (recordLen <= 0 || recordLen > 18432) { app.close(); return }
            val body = ByteArray(recordLen)
            readFully(rawIn, body)
            val record = byteArrayOf(first.toByte()) + headerRest + body
            val sni = SniParser.extract(record)
            handleTls(app, record, appOut, destIp, destPort, sni, uid)
        } else {
            // 读一行判断 HTTP method
            val lineBytes = ByteArrayOutputStream()
            lineBytes.write(first)
            var b: Int
            while (rawIn.read().also { b = it } >= 0) {
                lineBytes.write(b)
                if (b == '\n'.code || lineBytes.size() > 8192) break
            }
            val prefix = lineBytes.toByteArray()
            val line = String(prefix, Charsets.ISO_8859_1).trim()
            val method = line.substringBefore(' ').uppercase()
            val stream = BufferedInputStream(SequenceInputStream(ByteArrayInputStream(prefix), rawIn), 64 * 1024)
            if (method in HTTP_METHODS) {
                handlePlainHttp(app, stream, appOut, destIp, destPort, uid)
            } else {
                blindRelay(app, stream, appOut, destIp, destPort)
            }
        }
    }

    // ---------- 明文 HTTP ----------

    private fun handlePlainHttp(app: Socket, appIn: BufferedInputStream, appOut: BufferedOutputStream, destIp: String, destPort: Int, uid: Int) {
        val head = HttpCodec.readHead(appIn) ?: return
        val startParts = head.startLine.split(" ")
        if (startParts.size < 2) return
        val method = startParts[0].uppercase()
        val hostHeader = head.value("Host")
        val host = hostHeader?.substringBefore(':') ?: destIp
        val target = upstreamHost(host, destIp)
        FileLogger.log("HTTP $method $host${startParts.getOrElse(1) { "" }} -> $target:$destPort")

        val exchange = HttpExchange(scheme = "http", host = host, port = destPort, isTls = false)
        exchange.remoteIp = destIp
        exchange.uid = uid
        exchange.method = method
        exchange.path = normalizePath(startParts[1])
        exchange.requestHeaders = head.headers.toList()
        RequestStore.add(exchange)

        try {
            if (head.value("Expect")?.contains("100-continue", true) == true) {
                appOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                appOut.flush()
            }
            val reqBody = HttpCodec.readBody(appIn, head, isResponse = false, method = method)
            exchange.requestBody = cap(reqBody)

            Socket().use { up ->
                up.soTimeout = 30_000
                up.connect(InetSocketAddress(InetAddress.getByName(destIp), destPort), 10_000)
                val upIn = BufferedInputStream(up.getInputStream(), 64 * 1024)
                val upOut = BufferedOutputStream(up.getOutputStream(), 64 * 1024)
                HttpCodec.writeHeadAndBody(upOut, head.startLine, head.headers, reqBody)

                val respHead = HttpCodec.readHead(upIn)
                if (respHead == null) { fail(exchange, "empty response"); return }
                fillResponse(exchange, respHead)
                val respBody = HttpCodec.readBody(upIn, respHead, isResponse = true, method = method)
                exchange.responseBody = cap(respBody)
                complete(exchange)
                HttpCodec.writeHeadAndBody(appOut, respHead.startLine, respHead.headers, respBody)
            }
        } catch (e: Exception) {
            fail(exchange, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { app.close() }
        }
    }

    // ---------- HTTPS MITM ----------

    private fun handleTls(
        app: Socket, clientHello: ByteArray, appOut: BufferedOutputStream,
        destIp: String, destPort: Int, sni: String?, uid: Int
    ) {
        val host = sni ?: destIp
        // 该 host 此前 MITM 失败过 → 直接透传，保证 App 可用
        if (host in mitmBlacklist) {
            val stream = BufferedInputStream(SequenceInputStream(ByteArrayInputStream(clientHello), app.getInputStream()), 64 * 1024)
            blindRelay(app, stream, appOut, destIp, destPort,
                tls = true, hostLabel = sni, reason = "App 不信任 CA，自动透传")
            return
        }
        // 抓包模式（黑/白名单）：不命中的 host 透传不解析
        if (!CaptureMode.shouldMitm(appContext, host)) {
            FileLogger.log("TLS $host bypassed by capture mode")
            val stream = BufferedInputStream(SequenceInputStream(ByteArrayInputStream(clientHello), app.getInputStream()), 64 * 1024)
            blindRelay(app, stream, appOut, destIp, destPort,
                tls = true, hostLabel = sni, reason = "抓包模式（黑/白名单）排除")
            return
        }
        val serverCtx = try {
            CertAuthority.serverContext(appContext, host)
        } catch (e: Exception) {
            Log.w(TAG, "no cert for $host: ${e.message}")
            null
        }
        if (serverCtx == null) {
            val stream = BufferedInputStream(SequenceInputStream(ByteArrayInputStream(clientHello), app.getInputStream()), 64 * 1024)
            blindRelay(app, stream, appOut, destIp, destPort,
                tls = true, hostLabel = sni, reason = "站点证书生成失败，自动透传")
            return
        }

        // 与 App 完成 TLS 握手（回放已读的 ClientHello）
        val appTls = try {
            val replay = ReplaySocket(app, clientHello)
            val s = serverCtx.socketFactory.createSocket(replay, host, destPort, true) as SSLSocket
            s.useClientMode = false
            s.soTimeout = 30_000
            s.startHandshake()
            s
        } catch (e: Exception) {
            // 多半是 App 不信任我们的 CA（Android 7+ 默认不信任用户证书）。
            // 拉入黑名单：该 host 后续连接一律透传，不再打断 App。
            Log.d(TAG, "TLS handshake with app failed ($host): ${e.message}")
            mitmBlacklist.add(host)
            runCatching { app.close() }
            return
        }

        try {
            val tlsIn = BufferedInputStream(appTls.inputStream, 64 * 1024)
            val tlsOut = BufferedOutputStream(appTls.outputStream, 64 * 1024)

            val head = HttpCodec.readHead(tlsIn) ?: return
            val startParts = head.startLine.split(" ")
            if (startParts.size < 2) return
            val method = startParts[0].uppercase()
            val hostHeader = head.value("Host")?.substringBefore(':')

            val exchange = HttpExchange(scheme = "https", host = hostHeader ?: host, port = destPort, isTls = true)
            exchange.remoteIp = destIp
            exchange.uid = uid
            exchange.method = method
            exchange.path = normalizePath(startParts[1])
            exchange.requestHeaders = head.headers.toList()
            RequestStore.add(exchange)

            try {
                if (head.value("Expect")?.contains("100-continue", true) == true) {
                    tlsOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    tlsOut.flush()
                }
                val reqBody = HttpCodec.readBody(tlsIn, head, isResponse = false, method = method)
                exchange.requestBody = cap(reqBody)

                // 与真实服务器建 TLS（强制 http/1.1）
                createUpstreamTls(destIp, destPort, hostHeader ?: host).use { up ->
                    val upIn = BufferedInputStream(up.inputStream, 64 * 1024)
                    val upOut = BufferedOutputStream(up.outputStream, 64 * 1024)
                    HttpCodec.writeHeadAndBody(upOut, head.startLine, head.headers, reqBody)

                    val respHead = HttpCodec.readHead(upIn)
                    if (respHead == null) { fail(exchange, "empty response"); return }
                    fillResponse(exchange, respHead)
                    val respBody = HttpCodec.readBody(upIn, respHead, isResponse = true, method = method)
                    exchange.responseBody = cap(respBody)
                    complete(exchange)
                    HttpCodec.writeHeadAndBody(tlsOut, respHead.startLine, respHead.headers, respBody)
                }
            } catch (e: Exception) {
                fail(exchange, e.message ?: e.javaClass.simpleName)
            }
        } finally {
            runCatching { appTls.close() }
        }
    }

    private fun createUpstreamTls(destIp: String, destPort: Int, sniHost: String): SSLSocket {
        val ctx = SSLContext.getDefault()
        val raw = Socket()
        raw.soTimeout = 30_000
        raw.connect(InetSocketAddress(InetAddress.getByName(upstreamHost(sniHost, destIp)), destPort), 10_000)
        val tls = ctx.socketFactory.createSocket(raw, sniHost, destPort, true) as SSLSocket
        tls.useClientMode = true
        val params = tls.sslParameters
        params.applicationProtocols = arrayOf("http/1.1")
        tls.sslParameters = params
        tls.soTimeout = 30_000
        tls.startHandshake()
        return tls
    }

    // ---------- 盲转发 ----------

    /**
     * 盲转发（透传）。会记录一条未解密的连接元数据：
     * @param tls 是否嗅探到 TLS 但放弃解密
     * @param hostLabel 已解析的 SNI 域名（可为 null，此时展示目标 IP）
     * @param reason 透传原因，展示给用户
     */
    private fun blindRelay(
        app: Socket, appIn: InputStream, appOut: BufferedOutputStream,
        destIp: String, destPort: Int,
        tls: Boolean = false, hostLabel: String? = null, reason: String = "非 HTTP 流量"
    ) {
        Log.d(TAG, "blind relay to $destIp:$destPort ($reason)")
        val rec = PassthroughRec(
            host = hostLabel ?: destIp,
            port = destPort,
            tls = tls,
            reason = reason
        )
        RequestStore.addPassthrough(rec)
        val upstream = Socket()
        upstream.soTimeout = 60_000
        upstream.connect(InetSocketAddress(InetAddress.getByName(destIp), destPort), 10_000)
        try {
            val t = Thread {
                try {
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = upstream.getInputStream().read(buf)
                        if (n < 0) break
                        rec.downBytes.addAndGet(n.toLong())
                        appOut.write(buf, 0, n)
                        appOut.flush()
                    }
                } catch (_: Exception) {}
                runCatching { appOut.flush() }
                runCatching { app.close() }
            }
            t.isDaemon = true
            t.start()
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = appIn.read(buf)
                    if (n < 0) break
                    rec.upBytes.addAndGet(n.toLong())
                    upstream.getOutputStream().write(buf, 0, n)
                    upstream.getOutputStream().flush()
                }
            } catch (_: Exception) {}
        } finally {
            runCatching { upstream.close() }
            runCatching { app.close() }
            RequestStore.finishPassthrough(rec)
        }
    }

    // ---------- 工具 ----------

    private fun readLineRaw(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 4096) return null
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun fillResponse(e: HttpExchange, head: HttpCodec.Head) {
        val parts = head.startLine.split(" ", limit = 3)
        e.statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
        e.statusText = parts.getOrNull(2) ?: ""
        e.responseHeaders = head.headers.toList()
    }

    private fun complete(e: HttpExchange) {
        e.state = HttpExchange.State.COMPLETE
        e.endTime = System.currentTimeMillis()
        RequestStore.notifyChanged()
    }

    private fun fail(e: HttpExchange, msg: String) {
        e.state = HttpExchange.State.FAILED
        e.error = msg
        e.endTime = System.currentTimeMillis()
        FileLogger.log("FAIL ${e.method} ${e.host}${e.path}: $msg")
        RequestStore.notifyChanged()
    }

    private fun cap(body: ByteArray): ByteArray =
        if (body.size > HttpExchange.MAX_BODY) body.copyOf(HttpExchange.MAX_BODY) else body

    private fun normalizePath(raw: String): String =
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw.substringAfter("://").substringAfter("/", "/")
        } else raw
}
