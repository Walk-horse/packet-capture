package com.ht.stream.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 一次抓包会话（开始~停止） */
data class CaptureSession(
    val id: String = UUID.randomUUID().toString(),
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long = 0
) {
    val durationSec: Long get() = ((if (endTime > 0) endTime else System.currentTimeMillis()) - startTime) / 1000
}

/** 抓包记录的内存仓库，UI 通过 StateFlow 订阅 */
object RequestStore {
    private const val MAX_ENTRIES = 500
    private const val MAX_SESSIONS = 50
    private const val MAX_PASSTHROUGH = 400

    private val _exchanges = MutableStateFlow<List<HttpExchange>>(emptyList())
    val exchanges: StateFlow<List<HttpExchange>> = _exchanges

    private val _passthrough = MutableStateFlow<List<PassthroughRec>>(emptyList())
    val passthrough: StateFlow<List<PassthroughRec>> = _passthrough

    private val _sessions = MutableStateFlow<List<CaptureSession>>(emptyList())
    val sessions: StateFlow<List<CaptureSession>> = _sessions

    /** 当前进行中的 session（停止抓包后置 null） */
    @Volatile var currentSession: CaptureSession? = null
        private set

    /** 整机流量统计（经 TUN 的字节数） */
    val uploadBytes = AtomicLong(0)
    val downloadBytes = AtomicLong(0)

    /** 任意 exchange 字段变化时 +1，驱动详情页刷新 */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    @Synchronized
    fun startSession() {
        val s = CaptureSession()
        currentSession = s
        val list = _sessions.value.toMutableList()
        list.add(0, s)
        while (list.size > MAX_SESSIONS) list.removeAt(list.size - 1)
        _sessions.value = list
        uploadBytes.set(0)
        downloadBytes.set(0)
        notifyChanged()
    }

    @Synchronized
    fun endSession() {
        currentSession?.endTime = System.currentTimeMillis()
        currentSession = null
        notifyChanged()
    }

    @Synchronized
    fun add(e: HttpExchange) {
        currentSession?.let { e.sessionId = it.id }
        val list = _exchanges.value.toMutableList()
        list.add(0, e)
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        _exchanges.value = list
        notifyChanged()
    }

    fun notifyChanged() {
        _tick.value = System.nanoTime()
    }

    /** 透传连接：连接建立时记录（endTime=0 表示进行中） */
    @Synchronized
    fun addPassthrough(p: PassthroughRec) {
        currentSession?.let { p.sessionId = it.id }
        val list = _passthrough.value.toMutableList()
        list.add(0, p)
        while (list.size > MAX_PASSTHROUGH) list.removeAt(list.size - 1)
        _passthrough.value = list
        notifyChanged()
    }

    /** 透传连接结束时回填时长/字节并刷新 UI */
    @Synchronized
    fun finishPassthrough(p: PassthroughRec) {
        if (p.endTime == 0L) p.endTime = System.currentTimeMillis()
        notifyChanged()
    }

    @Synchronized
    fun toggleFavorite(id: String) {
        _exchanges.value.firstOrNull { it.id == id }?.let { it.favorite = !it.favorite }
        notifyChanged()
    }

    fun favorites(): List<HttpExchange> = _exchanges.value.filter { it.favorite }

    @Synchronized
    fun deleteExchanges(ids: Set<String>) {
        _exchanges.value = _exchanges.value.filterNot { it.id in ids }
        notifyChanged()
    }

    /** 清空全部历史（请求 + 透传 + 会话） */
    @Synchronized
    fun clearHistory() {
        _exchanges.value = emptyList()
        _passthrough.value = emptyList()
        _sessions.value = emptyList()
        notifyChanged()
    }

    /** 清空当前抓包页展示的数据（请求 + 未解密连接），并复位流量计数 */
    @Synchronized
    fun clearCurrent() {
        _exchanges.value = emptyList()
        _passthrough.value = emptyList()
        uploadBytes.set(0)
        downloadBytes.set(0)
        notifyChanged()
    }

    fun find(id: String): HttpExchange? = _exchanges.value.firstOrNull { it.id == id }

    fun ofSession(sessionId: String): List<HttpExchange> =
        _exchanges.value.filter { it.sessionId == sessionId }

    fun passthroughOfSession(sessionId: String): List<PassthroughRec> =
        _passthrough.value.filter { it.sessionId == sessionId }

    fun sessionRequestCount(sessionId: String): Int =
        _exchanges.value.count { it.sessionId == sessionId }

    fun sessionPassthroughCount(sessionId: String): Int =
        _passthrough.value.count { it.sessionId == sessionId }
}
