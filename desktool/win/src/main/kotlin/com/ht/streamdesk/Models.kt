package com.ht.streamdesk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// MARK: - 同步协议模型（与手机端 SyncJson / mac 版 Models.swift 字段一一对应）

@Serializable
data class SyncState(
    val capturing: Boolean = false,
    @SerialName("uploadBytes") val uploadBytes: Long = 0,
    @SerialName("downloadBytes") val downloadBytes: Long = 0,
    @SerialName("requestCount") val requestCount: Int = 0,
    @SerialName("passthroughCount") val passthroughCount: Int = 0,
    val full: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val passthrough: List<PassRecord> = emptyList(),
    val exchanges: List<ExchangeRecord> = emptyList(),
)

@Serializable
data class SessionInfo(
    val id: String,
    @SerialName("startTime") val startTime: Long = 0,
    @SerialName("endTime") val endTime: Long = 0,
    @SerialName("durationSec") val durationSec: Long = 0,
    @SerialName("requestCount") val requestCount: Int = 0,
    @SerialName("passthroughCount") val passthroughCount: Int = 0,
) {
    val startMillis: Long get() = startTime
}

@Serializable
data class PassRecord(
    val id: String,
    @SerialName("sessionId") val sessionId: String = "",
    val host: String = "",
    val port: Int = 0,
    val tls: Boolean = false,
    val reason: String = "",
    @SerialName("startTime") val startTime: Long = 0,
    @SerialName("endTime") val endTime: Long = 0,
    @SerialName("durationMs") val durationMs: Long = 0,
    @SerialName("upBytes") val upBytes: Long = 0,
    @SerialName("downBytes") val downBytes: Long = 0,
)

@Serializable
data class ExchangeRecord(
    val id: String,
    @SerialName("sessionId") val sessionId: String = "",
    val scheme: String = "",
    val host: String = "",
    val port: Int = 0,
    @SerialName("isTls") val isTls: Boolean = false,
    val method: String = "",
    val path: String = "",
    val url: String = "",
    @SerialName("statusCode") val statusCode: Int = 0,
    @SerialName("statusText") val statusText: String = "",
    val state: String = "",
    val error: String? = null,
    val favorite: Boolean = false,
    @SerialName("remoteIp") val remoteIp: String? = null,
    val uid: Int = -1,
    @SerialName("startTime") val startTime: Long = 0,
    @SerialName("endTime") val endTime: Long = 0,
    @SerialName("durationMs") val durationMs: Long = 0,
    @SerialName("requestHeaders") val requestHeaders: List<List<String>> = emptyList(),
    @SerialName("responseHeaders") val responseHeaders: List<List<String>> = emptyList(),
    @SerialName("respType") val respType: String? = null,
    @SerialName("reqType") val reqType: String? = null,
    @SerialName("requestBodyB64") val requestBodyB64: String = "",
    @SerialName("requestBodyTruncated") val requestBodyTruncated: Boolean = false,
    @SerialName("responseBodyB64") val responseBodyB64: String = "",
    @SerialName("responseBodyTruncated") val responseBodyTruncated: Boolean = false,
    @SerialName("reqEncoding") val reqEncoding: String? = null,
    @SerialName("respEncoding") val respEncoding: String? = null,
) {
    val isFailed: Boolean get() = state == "FAILED"
    val isPending: Boolean get() = state == "PENDING"
    val displayPath: String get() = if (path.isEmpty()) "/" else path

    val requestHeaderPairs: List<Pair<String, String>> get() = pairs(requestHeaders)
    val responseHeaderPairs: List<Pair<String, String>> get() = pairs(responseHeaders)

    fun header(pairsList: List<Pair<String, String>>, name: String): String? =
        pairsList.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun pairs(raw: List<List<String>>): List<Pair<String, String>> =
        raw.mapNotNull { row -> if (row.size >= 2) row[0] to row[1] else null }

    /** 不解码即可估算的大小（仅用于展示，避免为显示字节数解码整个 body） */
    val requestSize: Int get() = requestBodyB64.length * 3 / 4
    val responseSize: Int get() = responseBodyB64.length * 3 / 4

    val requestData: ByteArray get() = BodyCache.get("$id#req", requestBodyB64)
    val responseData: ByteArray get() = BodyCache.get("$id#resp", responseBodyB64)
}

/** body 解码缓存：避免列表/详情反复对同一条 body 做 base64 解码（LRU，上限 256 条 / 96MB） */
object BodyCache {
    private const val MAX_ENTRIES = 256
    private const val MAX_BYTES = 96L * 1024 * 1024

    private val lock = Any()
    private val map = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var bytes = 0L

    fun get(key: String, b64: String): ByteArray {
        synchronized(lock) {
            map[key]?.let { return it }
        }
        val decoded = decodeBase64(b64)
        synchronized(lock) {
            map[key] = decoded
            bytes += decoded.size
            while (map.size > MAX_ENTRIES || bytes > MAX_BYTES) {
                val eldest = map.entries.iterator()
                if (!eldest.hasNext()) break
                val e = eldest.next()
                bytes -= e.value.size
                eldest.remove()
            }
        }
        return decoded
    }

    fun clear() = synchronized(lock) {
        map.clear()
        bytes = 0
    }
}

/** 宽容的 base64 解码：容忍换行 / URL-safe 变体，失败返回空数组（与 mac 版一致） */
fun decodeBase64(b64: String): ByteArray {
    if (b64.isEmpty()) return ByteArray(0)
    val cleaned = buildString(b64.length) {
        for (c in b64) {
            when {
                c == '\n' || c == '\r' || c == ' ' || c == '\t' -> Unit
                c == '-' -> append('+')
                c == '_' -> append('/')
                else -> append(c)
            }
        }
    }
    return runCatching { java.util.Base64.getMimeDecoder().decode(cleaned) }.getOrElse { ByteArray(0) }
}

/** 侧栏可选项 */
sealed interface Panel {
    data object All : Panel
    data class Session(val id: String) : Panel
    data class Domain(val host: String) : Panel
    data object Passthrough : Panel
}
