package com.ht.stream.capture

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import java.io.File
import java.net.InetSocketAddress

/**
 * 把「TUN 内 App 连接的四元组」映射为发起进程的 uid。
 *
 * 解析路径（按优先级）：
 * 1. Android 10+：[ConnectivityManager.getConnectionOwnerUid]（公开 API，API 29 起）。
 *    由内核 `inet_diag` 按 (protocol, local, remote) 反查 socket 归属，
 *    并对「调用方是自己的 VPN 应用」放行——这正是 VpnService 场景。
 * 2. 回退：读 `/proc/net/tcp`。Android 10+ 起该文件对普通应用 **不可读**
 *    （`Permission denied`，SELinux），仅在有 root / 旧系统上有效。
 *
 * 注意：Android 10 之前 [uidOf] 会走 /proc；Android 10 及以上走 getConnectionOwnerUid。
 */
object UidResolver {
    private const val TAG = "UidResolver"

    /** 10.0.0.2 的十六进制（小端字节序，/proc/net/tcp 格式） */
    private const val TUN_HEX = "0A000002"
    private const val SCAN_INTERVAL_MS = 300L

    @Volatile private var cm: ConnectivityManager? = null

    /** /proc/net/tcp 是否可读（首次失败后置 false，避免反复刷错误日志） */
    @Volatile private var procReadable = true
    @Volatile private var procWarned = false

    private val portToUid = HashMap<Int, Int>()
    @Volatile private var lastScan = 0L

    /** 由 VpnService 在启动抓包时注入 Context */
    fun init(ctx: Context) {
        if (cm == null) {
            cm = runCatching {
                ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }.getOrNull()
        }
    }

    /**
     * 解析一条 (srcIp:srcPort → dstIp:dstPort) 连接所属的 uid。
     * [localIp] 是 TUN 侧源地址（10.0.0.2），[remoteIp] 是目标地址。
     * 解析不到返回 -1。
     */
    fun uidOf(localIp: String, localPort: Int, remoteIp: String, remotePort: Int): Int {
        if (localPort <= 0) return -1
        var how = "cm"
        var uid = ownerUidViaConnectivity(localIp, localPort, remoteIp, remotePort)
        if (uid == null) {
            how = "proc"
            uid = uidOfPort(localPort).takeIf { it >= 0 }
        }
        val resolved = uid ?: -1
        if (resolved < 0 || logCount < 20) {
            Log.d(TAG, "uid($localIp:$localPort→$remoteIp:$remotePort)=$resolved via=$how")
        }
        logCount++
        return resolved
    }

    private var logCount = 0

    // ---------- 路径 1：ConnectivityManager（Android 10+，VPN 应用可用）----------

    private fun ownerUidViaConnectivity(
        localIp: String, localPort: Int, remoteIp: String, remotePort: Int
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val c = cm ?: return null
        return try {
            val local = InetSocketAddress(localIp, localPort)
            val remote = InetSocketAddress(remoteIp, remotePort)
            val uid = c.getConnectionOwnerUid(Packet.PROTO_TCP, local, remote)
            if (uid >= 0) uid else null
        } catch (t: Throwable) {
            // SecurityException / IllegalArgumentException / 机型未实现
            if (cmFailLog < 10) {
                cmFailLog++
                Log.w(TAG, "getConnectionOwnerUid failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            null
        }
    }

    private var cmFailLog = 0

    // ---------- 路径 2：/proc/net/tcp（旧系统 / root）----------

    /** 返回该源端口对应 uid；解析不到返回 -1 */
    @Synchronized
    fun uidOfPort(srcPort: Int): Int {
        if (!procReadable) return -1
        val now = System.currentTimeMillis()
        if (portToUid.isEmpty() || now - lastScan > SCAN_INTERVAL_MS) {
            portToUid.clear()
            scan("/proc/net/tcp")
            lastScan = now
        }
        return portToUid[srcPort] ?: -1
    }

    private fun scan(path: String) {
        val lines = try {
            File(path).readLines()
        } catch (t: Throwable) {
            procReadable = false
            if (!procWarned) {
                procWarned = true
                Log.w(TAG, "$path unavailable (Android 10+ 已限制应用读取): ${t.message}")
            }
            return
        }
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
