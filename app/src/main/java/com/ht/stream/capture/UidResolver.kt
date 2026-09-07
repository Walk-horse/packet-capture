package com.ht.stream.capture

import java.io.File

/**
 * 把「TUN 内 App socket 的源端口」映射为发起进程的 uid。
 *
 * VPN 建立后（addAddress 10.0.0.2/32 + 默认路由），应用新建 socket 的
 * 本地地址即 TUN 地址 10.0.0.2，/proc/net/tcp 中该地址的条目带 uid 列，
 * 据此可以知道每条连接属于哪个 App（进程）。
 *
 * 端口号可复用，因此每次扫描结果只对「当前存活连接」有效；
 * 每次新连接（SYN）查询时若距上次扫描超过 [SCAN_INTERVAL_MS] 则重扫。
 */
object UidResolver {
    /** 10.0.0.2 的十六进制（小端字节序，/proc/net/tcp 格式） */
    private const val TUN_HEX = "0A000002"
    private const val SCAN_INTERVAL_MS = 300L

    @Volatile private var lastScan = 0L
    private val portToUid = HashMap<Int, Int>()

    /** 返回该源端口对应 uid；解析不到返回 -1 */
    @Synchronized
    fun uidOf(srcPort: Int): Int {
        val now = System.currentTimeMillis()
        if (portToUid.isEmpty() || now - lastScan > SCAN_INTERVAL_MS) {
            portToUid.clear()
            scan("/proc/net/tcp")
            lastScan = now
        }
        return portToUid[srcPort] ?: -1
    }

    private fun scan(path: String) {
        val lines = try { File(path).readLines() } catch (_: Exception) { return }
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) continue
            // local_address 形如 0A000002:1F90（HEXIP:HEXPORT）
            val local = parts[1]
            val idx = local.indexOf(':')
            if (idx < 0) continue
            if (!local.substring(0, idx).equals(TUN_HEX, ignoreCase = true)) continue
            val port = local.substring(idx + 1).toIntOrNull(16) ?: continue
            val uid = parts[7].toIntOrNull() ?: continue
            portToUid[port] = uid
        }
    }
}
