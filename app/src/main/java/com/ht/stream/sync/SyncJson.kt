package com.ht.stream.sync

import android.util.Base64
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.RequestStore
import com.ht.stream.ui.decodeBodyPreview
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桌面同步序列化：把 RequestStore 内存态导出为紧凑 JSON。
 *
 * 同步协议（增量为主，全量兜底）：
 *  - GET /api/state?after=<exchangeId>
 *  - exchanges 按时间倒序（index0 最新）。服务端返回列表中排在 afterId 之前（更新）的条目；
 *    若 afterId 不在列表（被裁剪/清空/首次连接）则 full=true 返回全量，客户端整体替换。
 *  - sessions / passthrough / 统计每次随响应全量下发（量小），客户端整体覆盖。
 *
 * 单条 body 超过 BODY_LIMIT 时截断同步（二进制大响应不需要全部搬上 Mac）。
 */
object SyncJson {

    /** 单条 body 同步上限 */
    const val BODY_LIMIT = 512 * 1024

    /** 同步文本上限：超长 body 只同步前列字符，Mac 端可切 HEX 看原始字节 */
    private const val TEXT_LIMIT = 256 * 1024

    fun state(afterId: String?): JSONObject {
        val exchanges = RequestStore.exchanges.value
        val idx = afterId?.let { id -> exchanges.indexOfFirst { it.id == id } } ?: -1
        val full = idx < 0
        val slice = if (full || exchanges.isEmpty()) exchanges
        else exchanges.subList(0, idx.coerceIn(0, exchanges.size))

        val obj = JSONObject()
        obj.put("capturing", CaptureVpnService.running.value)
        obj.put("uploadBytes", RequestStore.uploadBytes.get())
        obj.put("downloadBytes", RequestStore.downloadBytes.get())
        obj.put("requestCount", exchanges.size)
        obj.put("passthroughCount", RequestStore.passthrough.value.size)
        obj.put("full", full)
        obj.put("sessions", sessions())
        obj.put("passthrough", passthroughs())
        obj.put("exchanges", JSONArray().apply { slice.forEach { put(exchange(it)) } })
        return obj
    }

    private fun sessions(): JSONArray = JSONArray().apply {
        RequestStore.sessions.value.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("startTime", s.startTime)
            o.put("endTime", s.endTime)
            o.put("durationSec", s.durationSec)
            o.put("requestCount", RequestStore.sessionRequestCount(s.id))
            o.put("passthroughCount", RequestStore.sessionPassthroughCount(s.id))
            put(o)
        }
    }

    private fun passthroughs(): JSONArray = JSONArray().apply {
        RequestStore.passthrough.value.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("sessionId", p.sessionId)
            o.put("host", p.host)
            o.put("port", p.port)
            o.put("tls", p.tls)
            o.put("reason", p.reason)
            o.put("startTime", p.startTime)
            o.put("endTime", p.endTime)
            o.put("durationMs", p.durationMs)
            o.put("upBytes", p.upBytes.get())
            o.put("downBytes", p.downBytes.get())
            put(o)
        }
    }

    private fun exchange(e: HttpExchange): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("sessionId", e.sessionId)
        o.put("scheme", e.scheme)
        o.put("host", e.host)
        o.put("port", e.port)
        o.put("isTls", e.isTls)
        o.put("method", e.method)
        o.put("path", e.path)
        o.put("url", e.url)
        o.put("statusCode", e.statusCode)
        o.put("statusText", e.statusText)
        o.put("state", e.state.name)
        e.error?.let { o.put("error", it) }
        o.put("favorite", e.favorite)
        e.remoteIp?.let { o.put("remoteIp", it) }
        o.put("uid", e.uid)
        o.put("startTime", e.startTime)
        o.put("endTime", e.endTime)
        o.put("durationMs", e.durationMs)
        o.put("requestHeaders", headers(e.requestHeaders))
        o.put("responseHeaders", headers(e.responseHeaders))
        e.responseContentType?.let { o.put("respType", it) }
        e.requestContentType?.let { o.put("reqType", it) }

        val (reqB64, reqTrunc) = bodyB64(e.requestBody)
        val (respB64, respTrunc) = bodyB64(e.responseBody)
        o.put("requestBodyB64", reqB64)
        o.put("requestBodyTruncated", reqTrunc)
        o.put("responseBodyB64", respB64)
        o.put("responseBodyTruncated", respTrunc)

        // 手机端已按 Content-Encoding 解压并识别编码（gzip/deflate/brotli/zstd），
        // Mac 端无需自带解压库即可显示可读文本；二进制会给出格式提示。
        e.headerValue(e.requestHeaders, "Content-Encoding")?.let { o.put("reqEncoding", it) }
        e.headerValue(e.responseHeaders, "Content-Encoding")?.let { o.put("respEncoding", it) }
        decodedText(e, true)?.let { o.put("reqText", it) }
        decodedText(e, false)?.let { o.put("respText", it) }
        return o
    }

    private fun decodedText(e: HttpExchange, request: Boolean): String? =
        runCatching {
            decodeBodyPreview(e, request, TEXT_LIMIT)?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun headers(list: List<Pair<String, String>>): JSONArray = JSONArray().apply {
        list.forEach { (k, v) ->
            put(JSONArray().apply { put(k); put(v) })
        }
    }

    /** Base64(NO_WRAP) 编码，超限截断 */
    private fun bodyB64(body: ByteArray): Pair<String, Boolean> {
        if (body.isEmpty()) return "" to false
        val truncated = body.size > BODY_LIMIT
        val data = if (truncated) body.copyOfRange(0, BODY_LIMIT) else body
        return Base64.encodeToString(data, Base64.NO_WRAP) to truncated
    }
}
