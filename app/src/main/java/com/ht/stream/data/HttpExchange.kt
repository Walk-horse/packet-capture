package com.ht.stream.data

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 一条被抓取到的 HTTP(S) 请求/响应记录 */
class HttpExchange(
    val id: String = UUID.randomUUID().toString(),
    val scheme: String,          // http / https
    val host: String,
    val port: Int,
    val isTls: Boolean
) {
    enum class State { PENDING, COMPLETE, FAILED }

    var method: String = ""
    var path: String = ""
    var requestHeaders: List<Pair<String, String>> = emptyList()
    var requestBody: ByteArray = ByteArray(0)

    var statusCode: Int = 0
    var statusText: String = ""
    var responseHeaders: List<Pair<String, String>> = emptyList()
    var responseBody: ByteArray = ByteArray(0)

    var state: State = State.PENDING
    var error: String? = null

    /** 所属抓包 session id */
    var sessionId: String = ""

    /** 是否收藏 */
    var favorite: Boolean = false

    /** 实际连接的远端 IP */
    var remoteIp: String? = null

    /** 发起请求的应用进程 uid（-1 表示未能解析） */
    var uid: Int = -1

    val startTime: Long = System.currentTimeMillis()
    var endTime: Long = 0

    val url: String get() = "$scheme://$host${if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) "" else ":$port"}$path"
    val durationMs: Long get() = if (endTime > 0) endTime - startTime else -1

    fun headerValue(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, true) }?.second

    val responseContentType: String? get() = headerValue(responseHeaders, "Content-Type")
    val requestContentType: String? get() = headerValue(requestHeaders, "Content-Type")

    companion object {
        val counter = AtomicLong(0)
        const val MAX_BODY = 4 * 1024 * 1024 // body 存储上限 4MB（超出截断，尽量留全）
    }
}
