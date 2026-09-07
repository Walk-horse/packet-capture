package com.ht.stream.data

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条未解密（透传）的 TCP 连接记录。
 * 仅保留域名层元数据：host / 端口 / 起止 / 上下行字节 / 透传原因。
 */
class PassthroughRec(
    val id: String = UUID.randomUUID().toString(),
    /** SNI 域名；无 SNI（非 TLS / IP 直连）时为远端 IP */
    val host: String,
    val port: Int,
    /** 是否嗅探到 TLS 但未解密 */
    val tls: Boolean,
    /** 透传原因：证书不受信任 / 抓包模式排除 / 非 HTTP 流量 … */
    val reason: String,
    val startTime: Long = System.currentTimeMillis()
) {
    var endTime: Long = 0L
    var sessionId: String = ""

    /** 上行 App→服务器；下行 服务器→App */
    val upBytes = AtomicLong(0)
    val downBytes = AtomicLong(0)

    val durationMs: Long get() = if (endTime > 0) endTime - startTime else -1
}
