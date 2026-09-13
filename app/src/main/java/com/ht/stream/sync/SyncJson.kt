package com.ht.stream.sync

import android.util.Base64
import com.ht.stream.capture.CaptureVpnService
import com.ht.stream.data.HttpExchange
import com.ht.stream.data.PassthroughRec
import com.ht.stream.data.RequestStore
import com.ht.stream.ui.decompressBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桌面同步序列化：把 RequestStore 内存态导出为紧凑 JSON。
 *
 * 同步协议（以记录的 synced 标志位为准，增量为主，全量兜底）：
 *  - GET /api/state?full=1   全量同步：返回全部请求并记录其已同步，客户端整体替换
 *  - GET /api/state          增量同步：仅返回 synced=false 的请求（未同步部分），下发后置为已同步
 *  - WS 连接建立下发 snapshot（语义同增量：仅未同步部分），之后数据变化经 broadcastDelta 增量推送
 *  - sessions / passthrough / 统计每次随响应全量下发（量小），客户端整体覆盖
 *
 * 单条 body 超过 BODY_LIMIT 时截断同步（二进制大响应不需要全部搬上 Mac）。
 */
object SyncJson {

    /** 单条 body 同步上限（解压后截断，二进制大响应不需要全部搬上 Mac） */
    const val BODY_LIMIT = 512 * 1024

    /**
     * 生成同步响应。
     * @param full true=全量（返回全部并置已同步）；false=增量（仅未同步部分并置已同步）
     */
    fun state(full: Boolean): JSONObject {
        val sendEx = RequestStore.takeExchangesForSync(full)
        val obj = JSONObject()
        obj.put("capturing", CaptureVpnService.running.value)
        obj.put("uploadBytes", RequestStore.uploadBytes.get())
        obj.put("downloadBytes", RequestStore.downloadBytes.get())
        obj.put("requestCount", RequestStore.exchanges.value.size)
        obj.put("passthroughCount", RequestStore.passthrough.value.size)
        obj.put("startedAt", CaptureVpnService.startedAt.value)
        obj.put("full", full)
        obj.put("sessions", sessions())
        obj.put("passthrough", passthroughs())
        obj.put("exchanges", JSONArray().apply { sendEx.forEach { put(exchange(it)) } })
        return obj
    }

    /** WS 连接建立时的快照：默认按增量语义（仅未同步部分），full=true 时下发全部 */
    fun wsSnapshot(full: Boolean = false): JSONObject {
        val s = state(full)
        s.put("type", "snapshot")
        return s
    }

    /** WS 增量：下发「已筛选并置位」的请求列表（settled && !synced），附最新统计与全部透传。
     *  列表由调用方（SyncServer.broadcastDelta）通过 takeExchangesForSync(false) 取得，
     *  与 HTTP 增量共用同一 synced 标志位，去重靠桌面端 id 集合。 */
    fun wsDelta(send: List<HttpExchange>): JSONObject {
        val passNow = RequestStore.passthrough.value

        val obj = JSONObject()
        obj.put("type", "delta")
        obj.put("capturing", CaptureVpnService.running.value)
        obj.put("uploadBytes", RequestStore.uploadBytes.get())
        obj.put("downloadBytes", RequestStore.downloadBytes.get())
        obj.put("requestCount", RequestStore.exchanges.value.size)
        obj.put("passthroughCount", passNow.size)
        obj.put("startedAt", CaptureVpnService.startedAt.value)
        obj.put("exchanges", JSONArray().apply { send.forEach { put(exchange(it)) } })
        obj.put("passthrough", passthroughs())
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
        RequestStore.passthrough.value.forEach { p -> put(passthrough(p)) }
    }

    private fun passthrough(p: PassthroughRec): JSONObject {
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
        return o
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

        // 同步的 body 字节已在此处按 Content-Encoding 解压为明文并 base64，
        // Mac 端无需自带解压库即可显示可读文本；原始编码仅作信息保留。
        val (reqB64, reqTrunc) = syncBody(e, true)
        val (respB64, respTrunc) = syncBody(e, false)
        o.put("requestBodyB64", reqB64)
        o.put("requestBodyTruncated", reqTrunc)
        o.put("responseBodyB64", respB64)
        o.put("responseBodyTruncated", respTrunc)

        e.headerValue(e.requestHeaders, "Content-Encoding")?.let { o.put("reqEncoding", it) }
        e.headerValue(e.responseHeaders, "Content-Encoding")?.let { o.put("respEncoding", it) }
        return o
    }

    /**
     * 同步用的 body 字节：先按 Content-Encoding 解压（gzip/deflate/br/zstd），再按 BODY_LIMIT 截断并 base64。
     * 解压失败（未知/损坏编码）回退原文；二进制（无编码）原样传出。
     * 这样 Mac 端拿到的 responseBodyB64 即为可读明文（或原始二进制），同时避免旧方案
     * 「压缩字节 base64 + 解压文本」重复传输导致 payload 膨胀、且响应体显示依赖冗余字段的脆弱链路。
     */
    private fun syncBody(e: HttpExchange, request: Boolean): Pair<String, Boolean> {
        val raw = if (request) e.requestBody else e.responseBody
        if (raw.isEmpty()) return "" to false
        val headers = if (request) e.requestHeaders else e.responseHeaders
        val encoding = e.headerValue(headers, "Content-Encoding")?.lowercase()
        val bytes = decompressBody(encoding, raw) ?: raw
        val truncated = bytes.size > BODY_LIMIT
        val data = if (truncated) bytes.copyOfRange(0, BODY_LIMIT) else bytes
        return Base64.encodeToString(data, Base64.NO_WRAP) to truncated
    }

    private fun headers(list: List<Pair<String, String>>): JSONArray = JSONArray().apply {
        list.forEach { (k, v) ->
            put(JSONArray().apply { put(k); put(v) })
        }
    }
}
