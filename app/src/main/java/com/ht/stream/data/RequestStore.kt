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

    /**
     * 同步专用：返回待同步的请求记录并在返回时将其标记为已同步。
     * - full=true：返回全部「已落定」记录并把它们置为已同步（桌面端全量重新同步）
     * - full=false：仅返回「已落定且尚未同步」的记录并置已同步（自动增量同步）
     * 「已落定」= 状态不是 PENDING（即 COMPLETE / FAILED）。处于 PENDING 的请求响应体尚未到达，
     * 若此刻就标已同步，等响应回来也不会再下发，导致桌面端永远看到空响应体；因此 PENDING 排除在外，
     * 待其落定后下一轮同步（HTTP 轮询 / WS tick）自然会带上完整响应体。
     * 整个「读取 + 置位」在 synchronized 内完成，避免 HTTP 轮询与 WS 推送重复下发同一条。
     */
    @Synchronized
    fun takeExchangesForSync(full: Boolean): List<HttpExchange> {
        val settled = _exchanges.value.filter { it.state != HttpExchange.State.PENDING }
        return if (full) {
            settled.forEach { it.synced = true }
            settled
        } else {
            val pending = settled.filter { !it.synced }
            pending.forEach { it.synced = true }
            pending
        }
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
