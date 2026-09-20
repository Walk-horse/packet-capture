package com.ht.stream.capture

import android.content.Context
import android.app.ActivityManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

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

    private const val SCAN_INTERVAL_MS = 300L

    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var appContext: Context? = null
    private val isolatedUidToAppUid = HashMap<Int, Int>()

    /** /proc/net/tcp 是否可读（首次失败后置 false，避免反复刷错误日志） */
    @Volatile private var procReadable = true
    @Volatile private var procWarned = false

    private val portToUid = HashMap<Int, Int>()
    private val connectionToUid = HashMap<String, Int>()
    @Volatile private var lastScan = 0L

    /** 由 VpnService 在启动抓包时注入 Context */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
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
            uid = uidOfConnection(localPort, remoteIp, remotePort)
                .takeIf { it >= 0 }
        }
        val rawUid = uid ?: -1
        val resolved = canonicalAppUid(rawUid)
        if (resolved < 0 || logCount < 20) {
            val suffix = if (resolved != rawUid) " (isolated $rawUid -> app $resolved)" else ""
            Log.d(TAG, "uid($localIp:$localPort→$remoteIp:$remotePort)=$resolved via=$how$suffix")
        }
        logCount++
        return resolved
    }

    /**
     * 解析已经接入本地代理的客户端 socket 所属 UID。
     *
     * WebView 在启用系统 HTTP 代理时不会经过 TUN 的 DEST 前导路径，而是
     * 直接连接本地代理。此时这个 socket 的真实回环四元组仍由内核保留，
     * getConnectionOwnerUid 可以直接返回 WebView renderer 的 isolated UID。
     */
    fun uidOfSocket(socket: Socket): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        val local = socket.localSocketAddress as? InetSocketAddress ?: return -1
        val remote = socket.remoteSocketAddress as? InetSocketAddress ?: return -1
        val rawUid = ownerUidViaConnectivity(local, remote) ?: -1
        val resolved = canonicalAppUid(rawUid)
        if (resolved < 0 || rawUid != resolved) {
            Log.d(
                TAG,
                "socket uid(${local.address?.hostAddress}:${local.port}<-" +
                    "${remote.address?.hostAddress}:${remote.port})=$resolved raw=$rawUid"
            )
        }
        return resolved
    }

    /**
     * 正向代理的客户端连接在服务端进程中没有客户端 peer credentials，
     * 因此部分 ROM 无法通过 socket 四元组返回 WebView UID。此时使用当前
     * 前台/可见的第三方应用作为代理请求的归属，避免 H5 被显示为未知应用。
     */
    fun uidOfLocalProxyClient(socket: Socket): Int {
        val direct = uidOfSocket(socket)
        if (direct >= 0 && direct != Process.myUid()) return direct
        val fallback = foregroundAppUid()
        if (fallback >= 0) {
            Log.d(TAG, "proxy client uid fallback foreground=$fallback")
        }
        return fallback
    }

    @Synchronized
    private fun foregroundAppUid(): Int {
        val context = appContext ?: return -1
        val selfUid = Process.myUid()
        val am = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull() ?: return -1
        val processes = runCatching { am.runningAppProcesses.orEmpty() }.getOrNull().orEmpty()
        val ranked = processes
            .asSequence()
            .filter { it.uid >= 10000 && it.uid != selfUid }
            .filter {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            }
            .sortedWith(
                compareBy<ActivityManager.RunningAppProcessInfo> {
                    // Android WebView renderer 的 isolated UID 通常位于 99xxx，
                    // 且 pkgList 已包含宿主应用。优先它可避免多窗口时选错前台进程。
                    if (it.uid in 99000..99999) 0 else 1
                }.thenBy {
                    if (it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) 0 else 1
                }
            )
        for (process in ranked) {
            val packages = buildList {
                process.pkgList?.forEach { add(it) }
                process.processName?.substringBefore(':')?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.distinct()
            for (pkg in packages) {
                val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                val isRenderer = process.uid in 99000..99999
                if (info != null && info.uid != selfUid &&
                    (info.uid == process.uid || (isRenderer && info.uid >= 10000))) {
                    if (isRenderer) {
                        isolatedUidToAppUid[process.uid] = info.uid
                        Log.i(TAG, "foreground renderer uid=${process.uid} package=$pkg appUid=${info.uid}")
                    }
                    return info.uid
                }
            }
        }
        return -1
    }

    private var logCount = 0

    // ---------- 路径 1：ConnectivityManager（Android 10+，VPN 应用可用）----------

    private fun ownerUidViaConnectivity(
        localIp: String, localPort: Int, remoteIp: String, remotePort: Int
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val c = cm ?: return null
        val localCandidates = LinkedHashSet<String>().apply {
            add(localIp)
            // 部分 Android 机型不接受 TUN 地址，但接受 wildcard 或底层网卡地址。
            add("0.0.0.0")
            runCatching {
                c.allNetworks.forEach { network ->
                    c.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                        link.address.hostAddress?.let { add(it) }
                    }
                }
            }
        }
        val remote = InetSocketAddress(remoteIp, remotePort)
        var lastError: Throwable? = null
        for (candidate in localCandidates) {
            try {
                val uid = c.getConnectionOwnerUid(
                    Packet.PROTO_TCP,
                    InetSocketAddress(candidate, localPort),
                    remote
                )
                if (uid >= 0) return uid
            } catch (t: Throwable) {
                // SecurityException / IllegalArgumentException / 机型未实现，继续尝试下一个本地地址。
                lastError = t
            }
        }
        if (lastError != null && cmFailLog < 10) {
            cmFailLog++
            Log.w(TAG, "getConnectionOwnerUid failed: ${lastError.javaClass.simpleName}: ${lastError.message}")
        }
        return null
    }

    private fun ownerUidViaConnectivity(
        local: InetSocketAddress, remote: InetSocketAddress
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val c = cm ?: return null
        // inet_diag 通常要求发起端方向；对于本地代理 accept() 得到的是服务端方向，
        // 某些 ROM 只在反向传入时才会返回客户端 WebView 的 isolated UID。
        val candidates = listOf(local to remote, remote to local)
        for ((candidateLocal, candidateRemote) in candidates) {
            try {
                val uid = c.getConnectionOwnerUid(Packet.PROTO_TCP, candidateLocal, candidateRemote)
                if (uid >= 0) return uid
            } catch (t: Throwable) {
                if (cmSocketFailLog < 10) {
                    cmSocketFailLog++
                    Log.w(
                        TAG,
                        "getConnectionOwnerUid(socket) failed: " +
                            "${t.javaClass.simpleName}: ${t.message} " +
                            "local=${candidateLocal.address?.hostAddress}:${candidateLocal.port} " +
                            "remote=${candidateRemote.address?.hostAddress}:${candidateRemote.port}"
                    )
                }
            }
        }
        return null
    }

    private var cmFailLog = 0
    private var cmSocketFailLog = 0

    /**
     * WebView 多进程可能使用 isolated UID。该 UID 没有独立安装包，
     * 通过正在运行的 renderer 进程的 pkgList 反查宿主应用，再统一返回宿主 UID。
     */
    @Synchronized
    private fun canonicalAppUid(rawUid: Int): Int {
        if (rawUid < 0) return rawUid
        isolatedUidToAppUid[rawUid]?.let { return it }
        val context = appContext ?: return rawUid
        val pm = context.packageManager
        val directPackages = runCatching { pm.getPackagesForUid(rawUid) }.getOrNull()
        if (!directPackages.isNullOrEmpty()) return rawUid

        val am = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull() ?: return rawUid
        val process = runCatching {
            am.runningAppProcesses?.firstOrNull { it.uid == rawUid }
        }.getOrNull() ?: return rawUid
        val candidates = buildList {
            process.pkgList?.forEach { add(it) }
            process.processName?.substringBefore(':')?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()
        for (pkg in candidates) {
            val appUid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: continue
            if (appUid >= 0 && appUid != rawUid) {
                isolatedUidToAppUid[rawUid] = appUid
                Log.i(TAG, "mapped isolated uid=$rawUid process=${process.processName} package=$pkg appUid=$appUid")
                return appUid
            }
        }
        return rawUid
    }

    // ---------- 路径 2：/proc/net/tcp（旧系统 / root）----------

    /** 返回该源端口对应 uid；解析不到返回 -1 */
    @Synchronized
    fun uidOfPort(srcPort: Int): Int {
        refreshProcCache()
        return portToUid[srcPort] ?: -1
    }

    /**
     * Android VPN 的 TUN 源地址不一定会出现在 /proc/net/tcp 的 local_address 中。
     * 旧实现只匹配 10.0.0.2，导致即使 /proc 可读也全部返回未知进程；这里按
     * 本地端口 + 远端四元组匹配，并用端口作为最后回退。
     */
    @Synchronized
    private fun uidOfConnection(localPort: Int, remoteIp: String, remotePort: Int): Int {
        refreshProcCache()
        val remoteHex = ipv4ProcHex(remoteIp)
        val exact = remoteHex?.let { connectionToUid[connectionKey(localPort, it, remotePort)] }
        return exact ?: portToUid[localPort] ?: -1
    }

    private fun refreshProcCache() {
        if (!procReadable) return
        val now = System.currentTimeMillis()
        if (portToUid.isNotEmpty() && now - lastScan <= SCAN_INTERVAL_MS) return
        portToUid.clear()
        connectionToUid.clear()
        scan("/proc/net/tcp")
        scan("/proc/net/tcp6")
        lastScan = now
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
            // local_address / rem_address 形如 0A000002:1F90（HEXIP:HEXPORT）
            val local = parts[1]
            val remote = parts[2]
            val localIdx = local.lastIndexOf(':')
            val remoteIdx = remote.lastIndexOf(':')
            if (localIdx < 0 || remoteIdx < 0) continue
            val port = local.substring(localIdx + 1).toIntOrNull(16) ?: continue
            val remoteIp = remote.substring(0, remoteIdx).uppercase()
            val remotePort = remote.substring(remoteIdx + 1).toIntOrNull(16) ?: continue
            val uid = parts[7].toIntOrNull() ?: continue
            portToUid[port] = uid
            connectionToUid[connectionKey(port, remoteIp, remotePort)] = uid
        }
    }

    private fun connectionKey(localPort: Int, remoteIpHex: String, remotePort: Int): String =
        "$localPort|$remoteIpHex|$remotePort"

    private fun ipv4ProcHex(ip: String): String? {
        val octets = ip.split('.')
        if (octets.size != 4) return null
        val values = octets.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
        return values.asReversed().joinToString("") { "%02X".format(it) }
    }
}
