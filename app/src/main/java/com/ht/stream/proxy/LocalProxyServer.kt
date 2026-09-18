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

        /** 清除动态站点证书缓存。 */
        fun clearMitmCache() {
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

        // 首行可能是两种来源：
        //  - TUN 中继来的连接：以 "DEST <ip> <port> [uid]" 前导行开头
        //  - 系统代理转发来的连接（开启「HTTPS 代理」后，WebView/H5 等走此路径）：
        //    正向代理协议，首行为 "CONNECT host:port" 或 "GET http://... " 等
        val firstLine = readLineRaw(rawIn) ?: return
        if (firstLine.startsWith("DEST ")) {
            val parts = firstLine.split(" ")
            val destIp = parts.getOrNull(1) ?: return
            val destPort = parts.getOrNull(2)?.toIntOrNull() ?: return
            val uid = parts.getOrNull(3)?.toIntOrNull() ?: -1
            dispatchTun(app, rawIn, appOut, destIp, destPort, uid)
        } else {
            handleForwardProxy(app, rawIn, appOut, firstLine)
        }
    }

    /** TUN 中继来的连接（原逻辑）：根据首字节分流 TLS / 明文 HTTP / 其他 */
    private fun dispatchTun(
        app: Socket, rawIn: InputStream, appOut: BufferedOutputStream,
        destIp: String, destPort: Int, uid: Int
    ) {
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

    /**
     * 处理「系统代理」转发来的正向代理连接（开启「HTTPS 代理」后，WebView/H5 等走此路径）。
     *  - CONNECT host:port → 回 200 Connection Established，然后对隧道内 TLS 做 MITM（可抓 H5）
     *  - GET http://...    → 改写请求行为 origin-form 后当明文 HTTP 转发
     *  - 其它（含 https:// 绝对 URI 等）→ 作为 TCP 隧道盲转，保证 App 不报错断网
     */
    private fun handleForwardProxy(
        app: Socket, rawIn: InputStream, appOut: BufferedOutputStream, firstLine: String
    ) {
        val line = firstLine.trim()
        // 正向代理请求行是 METHOD target HTTP/version；必须只取第二列。
        // 否则 GET 的 URI 解析失败，CONNECT 的端口会混入 HTTP/1.1。
        val requestParts = line.split(Regex("\\s+"), limit = 3)
        val method = requestParts.getOrNull(0)?.uppercase() ?: return
        val target = requestParts.getOrNull(1) ?: return

        if (method == "CONNECT") {
            val authority = parseAuthority(target, 443) ?: return
            val host = authority.first
            val port = authority.second
            // 丢弃 CONNECT 请求行之后的请求头（Proxy-Connection 等），否则其字节会污染隧道内的 ClientHello
            readHeadBlock(rawIn)
            // 回 200 建立隧道（参考 ProxyPin http_proxy_handle.dart）
            appOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            appOut.flush()
            FileLogger.log("PROXY CONNECT $host:$port")
            // 读隧道内的 ClientHello（与 TUN 路径一致）
            val first = rawIn.read()
            if (first < 0) { runCatching { app.close() }; return }
            if (first != 0x16) {
                val stream = BufferedInputStream(
                    SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), rawIn), 64 * 1024)
                blindRelay(app, stream, appOut, host, port,
                    tls = false, hostLabel = host, reason = "CONNECT 隧道非 TLS 流量")
                return
            }
            val headerRest = ByteArray(4)
            readFully(rawIn, headerRest)
            val recordLen = ((headerRest[2].toInt() and 0xFF) shl 8) or (headerRest[3].toInt() and 0xFF)
            if (recordLen <= 0 || recordLen > 18432) { runCatching { app.close() }; return }
            val body = ByteArray(recordLen)
            readFully(rawIn, body)
            val record = byteArrayOf(first.toByte()) + headerRest + body
            val sni = SniParser.extract(record)
            handleTls(app, record, appOut, host, port, sni, -1)
            return
        }

        // 明文 HTTP 正向代理（GET http://...）：改写请求行为 origin-form 后转发
        if (target.startsWith("http://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(target) }.getOrNull()
            if (uri?.host != null) {
                val host = uri.host
                val port = if (uri.port > 0) uri.port else 80
                val path = (uri.path ?: "") + if (uri.query != null) "?${uri.query}" else ""
                val originLine = "$method ${if (path.isEmpty()) "/" else path} HTTP/1.1"
                val headBlock = readHeadBlock(rawIn)
                val cut = String(headBlock, Charsets.ISO_8859_1).indexOf("\r\n")
                val rest = if (cut >= 0) headBlock.copyOfRange(cut + 2, headBlock.size) else headBlock
                val rebuilt = "$originLine\r\n".toByteArray() + rest
                val stream = BufferedInputStream(
                    SequenceInputStream(ByteArrayInputStream(rebuilt), rawIn), 64 * 1024)
                handlePlainHttp(app, stream, appOut, host, port, -1)
                return
            }
        }

        // 其它（含 https:// 绝对 URI 等少见情形）：作为 TCP 隧道盲转，保活不报错
        val hp = parseHostPortFromTarget(target)
        if (hp != null) {
            FileLogger.log("PROXY tunnel $method -> ${hp.first}:${hp.second}")
            val stream = BufferedInputStream(
                SequenceInputStream(ByteArrayInputStream("$firstLine\r\n".toByteArray()), rawIn), 64 * 1024)
            blindRelay(app, stream, appOut, hp.first, hp.second, reason = "正向代理隧道透传: $method")
        } else {
            runCatching { app.close() }
        }
    }

    /** 从绝对 URI（http://host:port/ 或 https://host:port/）中解析 host:port */
    private fun parseHostPortFromTarget(target: String): Pair<String, Int>? {
        val m = Regex("""^[a-zA-Z]+://([^/:?#]+)(?::(\d+))?""").find(target) ?: return null
        val host = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: if (target.startsWith("https", true)) 443 else 80
        return host to port
    }

    /** 解析 CONNECT authority，兼容 host:port 与 [IPv6]:port。 */
    private fun parseAuthority(target: String, defaultPort: Int): Pair<String, Int>? {
        val value = target.trim()
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1) return null
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val colon = value.lastIndexOf(':')
        if (colon <= 0) return value to defaultPort
        return value.substring(0, colon) to (value.substring(colon + 1).toIntOrNull() ?: defaultPort)
    }

    /** 读取 HTTP 头块（到 \r\n\r\n 为止，含结尾 CRLF），用于改写正向代理请求行 */
    private fun readHeadBlock(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val tail = ByteArray(4)
        var n = 0
        while (true) {
            val c = input.read()
            if (c < 0) break
            out.write(c)
            if (n < 4) tail[n] = c.toByte()
            else { tail[0] = tail[1]; tail[1] = tail[2]; tail[2] = tail[3]; tail[3] = c.toByte() }
            n++
            if (n >= 4 && tail[0] == '\r'.code.toByte() && tail[1] == '\n'.code.toByte()
                && tail[2] == '\r'.code.toByte() && tail[3] == '\n'.code.toByte()) break
        }
        return out.toByteArray()
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

            // 接口模拟：命中规则则直接返回模拟响应，不访问真实服务器
            val mockRule = MockEngine.match(appContext, uid, method, host, exchange.path)
            if (mockRule != null) {
                val resp = MockEngine.respond(appContext, mockRule, method, host, exchange.path)
                exchange.statusCode = resp.statusCode
                exchange.statusText = resp.statusText
                exchange.responseHeaders = resp.headers
                exchange.responseBody = cap(resp.body)
                exchange.mocked = true
                exchange.mockAuto = resp.autoGenerated
                complete(exchange)
                FileLogger.log("MOCK $method $host${exchange.path} -> ${resp.statusCode} ${resp.body.size}B${if (resp.autoGenerated) " (自动示例)" else ""}")
                HttpCodec.writeHeadAndBody(appOut, "HTTP/1.1 ${resp.statusCode} ${resp.statusText}", resp.headers, resp.body)
                return
            }

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
        // 抓包模式（黑/白名单）：不命中的 host 透传不解析。
        // 例外：该 host 配了接口模拟规则时必须解密，否则看不到请求就无法模拟。
        if (!CaptureMode.shouldMitm(appContext, host) && !MockEngine.shouldForceMitm(appContext, host)) {
            FileLogger.log("TLS $host bypassed by capture mode")
            val stream = BufferedInputStream(SequenceInputStream(ByteArrayInputStream(clientHello), app.getInputStream()), 64 * 1024)
            blindRelay(app, stream, appOut, destIp, destPort,
                tls = true, hostLabel = sni, reason = "抓包模式（黑/白名单）排除")
            return
        }
        val serverCtx = try {
            CertAuthority.serverContext(appContext, host)
        } catch (e: Exception) {
            Log.w(TAG, "no MITM certificate for host=$host sni=$sni", e)
            FileLogger.log("TLS $host 站点证书生成失败，连接已关闭: ${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}")
            null
        }
        if (serverCtx == null) {
            // TLS 已被识别为需要 MITM；证书上下文失败时不能伪装成普通透传。
            // 否则 UI 只显示“透传”，实际的 CA/证书生成错误会被隐藏。
            runCatching { app.close() }
            return
        }

        // 与 App 完成 TLS 握手（回放已读的 ClientHello）
        val appTls = try {
            val replay = ReplaySocket(app, clientHello)
            val s = serverCtx.socketFactory.createSocket(replay, host, destPort, true) as SSLSocket
            s.useClientMode = false
            // 代理层当前按 HTTP/1.1 解析，明确选择该协议，避免 WebView 看到未协商
            // ALPN 后继续按 HTTP/2 发送二进制帧，最终表现为 SSL protocol error。
            s.sslParameters = s.sslParameters.apply {
                applicationProtocols = arrayOf("http/1.1")
            }
            s.wantClientAuth = false
            s.needClientAuth = false
            s.soTimeout = 30_000
            s.startHandshake()
            s
        } catch (e: Exception) {
            // 这里不能回退为透传：SSLSocket 可能已经向 App 发出了 ServerHello/证书，
            // 此时再把同一个 ClientHello 发送给真实服务器会把两套 TLS 会话拼到一起，
            // WebView 会报 ERR_SSL_PROTOCOL_ERROR / "Failure in SSL library"。
            // 直接关闭连接，保留真实握手异常，便于区分 unknown_ca、SSL pinning 和协议问题。
            val detail = buildString {
                append(e.javaClass.simpleName)
                append(": ")
                append(e.message ?: "无错误信息")
                var cause = e.cause
                var depth = 0
                while (cause != null && depth++ < 3) {
                    append("; cause=")
                    append(cause.javaClass.simpleName)
                    append(": ")
                    append(cause.message ?: "")
                    cause = cause.cause
                }
            }
            Log.w(TAG, "TLS MITM handshake failed host=$host sni=$sni: $detail", e)
            FileLogger.log("TLS $host MITM 失败，连接已关闭: $detail")
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

                // 接口模拟：命中规则则直接返回模拟响应，不访问真实服务器
                val mockRule = MockEngine.match(appContext, uid, method, exchange.host, exchange.path)
                if (mockRule != null) {
                    val resp = MockEngine.respond(appContext, mockRule, method, exchange.host, exchange.path)
                    exchange.statusCode = resp.statusCode
                    exchange.statusText = resp.statusText
                    exchange.responseHeaders = resp.headers
                    exchange.responseBody = cap(resp.body)
                    exchange.mocked = true
                    exchange.mockAuto = resp.autoGenerated
                    complete(exchange)
                    FileLogger.log("MOCK $method ${exchange.host}${exchange.path} -> ${resp.statusCode} ${resp.body.size}B${if (resp.autoGenerated) " (自动示例)" else ""}")
                    HttpCodec.writeHeadAndBody(tlsOut, "HTTP/1.1 ${resp.statusCode} ${resp.statusText}", resp.headers, resp.body)
                    return
                }

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
