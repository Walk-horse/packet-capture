package com.ht.streamdesk

import androidx.compose.ui.graphics.Color
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json


/** 调试日志：直接落盘，避免 stdout 经过 gradle/管道层丢失 */
internal fun logd(msg: String) {
    runCatching {
        java.io.File("/tmp/streamdesk-debug.log")
            .appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
    }
}

/** 已连接的 USB 设备 */
data class UsbDevice(val serial: String, val model: String)

/** 同步状态（对齐 mac 版 SyncClient.Status） */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Ok(val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus

    val text: String
        get() = when (this) {
            Idle -> "未连接"
            Syncing -> "同步中…"
            is Ok -> "已同步 ${clock.format(java.util.Date(at))}"
            is Failed -> "失败：$message"
        }

    val color: Color
        get() = when (this) {
            Idle -> Color(0xFF9E9E9E)
            Syncing -> Color(0xFFF57C00)
            is Ok -> Color(0xFF2E7D32)
            is Failed -> Color(0xFFE53935)
        }

    private companion object {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
}

/** 手机端同步客户端：定时增量拉取 /api/state，支持自动同步与手动同步 */
class SyncClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val prefs: Preferences = Preferences.userRoot().node("com/ht/streamdesk"),
    private val http: (String) -> String = ::httpGet,
) {
    data class Stats(
        val capturing: Boolean = false,
        val upload: Long = 0,
        val download: Long = 0,
        val requests: Int = 0,
        val passthrough: Int = 0,
    )

    val address = MutableStateFlow(prefs.get(KEY_ADDRESS, ""))
    val autoSync = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val interval = MutableStateFlow(prefs.getDouble(KEY_INTERVAL, 1.5))
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val exchanges = MutableStateFlow<List<ExchangeRecord>>(emptyList())
    val sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val passthrough = MutableStateFlow<List<PassRecord>>(emptyList())
    val stats = MutableStateFlow(Stats())
    val devices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val selectedSerial = MutableStateFlow<String?>(null)
    val needsPick = MutableStateFlow(false)

    /** 本地最多保留条数（超出丢弃最旧的，与手机端 500 上限解耦） */
    private val maxLocal = 2000
    private var lastId: String? = null
    private val ids = HashSet<String>()
    private var timerJob: Job? = null
    @Volatile private var inFlight = false

    private val decoder = Json { ignoreUnknownKeys = true; isLenient = true }

    // MARK: - 配置

    fun setAddress(v: String) {
        address.value = v
        prefs.put(KEY_ADDRESS, v)
    }

    fun setAutoSync(on: Boolean) {
        autoSync.value = on
        prefs.putBoolean(KEY_AUTO, on)
        schedule()
    }

    fun setInterval(value: Double) {
        interval.value = value
        prefs.putDouble(KEY_INTERVAL, value)
        schedule()
    }

    /** 启动自动同步（首次进入或地址变更后调用） */
    fun startIfNeeded() {
        if (exchanges.value.isEmpty()) scope.launch { pull() }
        schedule()
    }

    private fun schedule() {
        timerJob?.cancel()
        timerJob = null
        if (!autoSync.value || interval.value <= 0) return
        timerJob = scope.launch {
            while (isActive) {
                delay((interval.value * 1000).toLong())
                pull()
            }
        }
    }

    fun close() {
        timerJob?.cancel()
    }

    // MARK: - USB 设备选择

    /**
     * 通过 adb 探测所有已授权的 USB 手机：
     * - 0 台：报错
     * - 1 台：自动连接
     * - 多台：置 needsPick，由界面弹出选择器让用户选
     */
    suspend fun detectUsb() {
        val adb = Adb.path()
        logd("detectUsb adb=$adb")
        if (adb == null) {
            status.value = SyncStatus.Failed("未找到 adb，请安装 Android SDK 平台工具（或把 adb 目录加入 PATH）")
            return
        }
        try {
            val list = Adb.parseDeviceList(Adb.run(listOf("devices", "-l"), adb))
            logd("devices=${list.map { it.serial to it.model }}")
            devices.value = list
            when {
                list.isEmpty() -> {
                    needsPick.value = false
                    status.value = SyncStatus.Failed("未发现已授权的 USB 设备（请确认 USB 调试已开启）")
                }
                list.size == 1 -> {
                    needsPick.value = false
                    selectDevice(list[0])
                }
                else -> needsPick.value = true
            }
        } catch (e: Exception) {
            status.value = SyncStatus.Failed(friendly(e))
        }
    }

    /**
     * 选择某台 USB 设备作为同步源：
     * 为该设备分配独立本地端口（17890 + 序号）做 adb forward，避免多机抢占同一端口。
     */
    suspend fun selectDevice(dev: UsbDevice) {
        val adb = Adb.path()
        if (adb == null) {
            status.value = SyncStatus.Failed("未找到 adb，请安装 Android SDK 平台工具")
            return
        }
        val idx = devices.value.indexOfFirst { it.serial == dev.serial }
        if (idx < 0) return
        val localPort = 17890 + idx
        status.value = SyncStatus.Syncing
        try {
            runCatching { Adb.run(listOf("forward", "--remove", "tcp:$localPort"), adb) }
            // forward 偶发失败（设备刚连上/VPN 切换时 adb 忙），重试一次
            var ok = runCatching {
                Adb.run(listOf("-s", dev.serial, "forward", "tcp:$localPort", "tcp:17890"), adb)
                true
            }.getOrDefault(false)
            if (!ok) {
                delay(1000)
                ok = runCatching {
                    Adb.run(listOf("-s", dev.serial, "forward", "tcp:$localPort", "tcp:17890"), adb)
                    true
                }.getOrDefault(false)
            }
            logd("selectDevice ${dev.serial} forwardOk=$ok")
            val target = if (ok) {
                "127.0.0.1:$localPort"
            } else {
                // 仅信任 wlan0 的地址解析；不要用 route 兜底（可能拿到蜂窝/VPN 的不可达 IP）
                "${Adb.wifiIp(dev.serial, adb)}:17890"
            }
            setAddress(target)
            selectedSerial.value = dev.serial
            resetAndPull()
        } catch (e: Exception) {
            logd("selectDevice 失败: ${e.message}")
            status.value = SyncStatus.Failed(friendly(e))
        }
    }

    // MARK: - 同步

    /** 增量同步一次；full = true 时忽略游标，整体替换本地数据 */
    suspend fun pull(full: Boolean = false) {
        if (inFlight) return
        val url = makeURL(full)
        if (url == null) {
            status.value = SyncStatus.Failed("请先在工具栏填写手机同步地址")
            return
        }
        inFlight = true
        status.value = SyncStatus.Syncing
        try {
            if (full) logd("pull 全量 url=$url")
            val body = http(url)
            val state = decoder.decodeFromString(SyncState.serializer(), body)
            apply(state)
            status.value = SyncStatus.Ok(System.currentTimeMillis())
        } catch (e: Exception) {
            logd("pull 失败: ${e.javaClass.simpleName}: ${e.message}")
            status.value = SyncStatus.Failed(friendly(e))
        } finally {
            inFlight = false
        }
    }

    /** 全量重新同步：清空游标与本地缓存后整体拉取（手机端清空历史后用它对齐） */
    suspend fun resetAndPull() {
        lastId = null
        ids.clear()
        pull(full = true)
    }

    fun clearLocal() {
        exchanges.value = emptyList()
        sessions.value = emptyList()
        passthrough.value = emptyList()
        ids.clear()
        lastId = null
        stats.value = Stats()
        status.value = SyncStatus.Idle
    }

    /**
     * 清屏：仅清空本地已同步的「请求列表」显示（不动手机端、不动会话/透传/统计）。
     * 后续同步会重新拉取并显示新请求，等价于浮动窗「清屏」隐藏可见记录的行为。
     */
    fun clearScreen() {
        exchanges.value = emptyList()
        ids.clear()
        lastId = null
    }

    // MARK: - 内部

    private fun apply(s: SyncState) {
        stats.value = Stats(
            capturing = s.capturing,
            upload = s.uploadBytes,
            download = s.downloadBytes,
            requests = s.requestCount,
            passthrough = s.passthroughCount,
        )
        sessions.value = s.sessions
        passthrough.value = s.passthrough

        var list = exchanges.value
        if (s.full) {
            list = s.exchanges
            ids.clear()
            s.exchanges.forEach { ids.add(it.id) }
        } else {
            val fresh = s.exchanges.filter { ids.add(it.id) }
            if (fresh.isNotEmpty()) list = fresh + list
        }
        if (list.size > maxLocal) {
            list.drop(maxLocal).forEach { ids.remove(it.id) }
            list = list.take(maxLocal)
        }
        exchanges.value = list
        lastId = list.firstOrNull()?.id
    }

    /** 地址规范化：允许粘贴完整 /api/state 地址或带尾斜杠 */
    internal fun makeURL(full: Boolean): String? {
        var base = address.value.trim()
        if (base.isEmpty()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://$base"
        base = base.replace("/api/state", "")
        base = base.trimEnd('/')

        // 手机端协议已改为 synced 标志位（与 mac 版一致）：
        // 增量 = 不带参数（只回 synced=false）；全量 = ?full=1（回全部并全置位）。
        // 旧的 ?after= 游标已废弃，手机端不识别。
        var path = "$base/api/state"
        if (full) path += "?full=1"
        return path
    }

    private fun friendly(e: Throwable): String = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            "无法连接手机（检查 Wi-Fi 是否同网、同步开关是否打开）"
        is SocketTimeoutException -> "连接超时"
        is IOException -> e.message ?: "网络错误"
        else -> e.message ?: e.toString()
    }

    private companion object {
        const val KEY_ADDRESS = "desktool.address"
        const val KEY_AUTO = "desktool.autoSync"
        const val KEY_INTERVAL = "desktool.interval"
    }
}

/** 极简 HTTP GET（不引入额外网络库；局域网明文 HTTP） */
internal fun httpGet(url: String, timeoutMs: Int = 12_000): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
        conn.disconnect()
    }
}

/** adb 封装（Windows / macOS 通用路径探测） */
object Adb {
    fun path(): String? {
        val exe = if (isWindows) "adb.exe" else "adb"
        val candidates = buildList {
            System.getenv("ANDROID_HOME")?.let { add(File(it, "platform-tools/$exe").path) }
            System.getenv("ANDROID_SDK_ROOT")?.let { add(File(it, "platform-tools/$exe").path) }
            val home = System.getProperty("user.home") ?: ""
            add(File(home, "AppData/Local/Android/Sdk/platform-tools/$exe").path)
            add(File(home, "Library/Android/sdk/platform-tools/$exe").path)
            add(File(home, "Android/Sdk/platform-tools/$exe").path)
            add("/usr/local/bin/$exe")
            add("/opt/homebrew/bin/$exe")
        }
        candidates.firstOrNull { File(it).canExecute() || File(it).isFile }?.let { return it }
        val sep = if (isWindows) ";" else ":"
        System.getenv("PATH")?.split(sep)?.forEach { dir ->
            val p = File(dir, exe)
            if (p.isFile) return p.path
        }
        return null
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    fun run(args: List<String>, adb: String, timeoutMs: Long = 15_000): String {
        val pb = ProcessBuilder(listOf(adb) + args).redirectErrorStream(false)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            throw IOException("adb 超时：${args.joinToString(" ")}")
        }
        if (p.exitValue() != 0) {
            val msg = (if (err.isBlank()) out else err).trim()
            throw IOException(msg.ifBlank { "adb 退出码 ${p.exitValue()}" })
        }
        return out
    }

    fun parseDeviceList(output: String): List<UsbDevice> {
        val res = mutableListOf<UsbDevice>()
        for (line in output.split('\n')) {
            if (line.isBlank() || line.startsWith("List of devices")) continue
            val parts = line.split('\t', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[1] != "device") continue
            val serial = parts[0]
            if (serial.startsWith("emulator")) continue
            var model = ""
            for (kv in parts.drop(2)) if (kv.startsWith("model:")) model = kv.removePrefix("model:")
            res.add(UsbDevice(serial, model))
        }
        return res
    }

    private val inetRe = Regex("""inet\s+(\d{1,3}(?:\.\d{1,3}){3})""")

    /**
     * 解析手机 Wi-Fi IP：只信任 wlan0（其次 eth0）上的 inet 地址。
     * 注意不要用 `ip route get` 兜底——抓包 VPN 开启时默认路由指向 TUN，
     * 会拿到 10.0.0.2 之类不可达的地址，甚至拿到蜂窝网络 IP。
     */
    fun wifiIp(serial: String, adb: String): String {
        for (iface in listOf("wlan0", "eth0")) {
            val out = runCatching {
                run(listOf("-s", serial, "shell", "ip", "-4", "addr", "show", iface), adb)
            }.getOrDefault("")
            inetRe.find(out)?.let { return it.groupValues[1] }
        }
        throw IOException("未获取到手机 Wi-Fi IP（请手动填写地址，如 192.168.x.x:17890）")
    }
}
