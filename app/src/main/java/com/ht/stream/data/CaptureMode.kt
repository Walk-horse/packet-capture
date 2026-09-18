package com.ht.stream.data

import android.content.Context

/**
 * 抓包模式：
 *  - 黑名单模式：名单内域名不解密（直接透传），其余正常抓包
 *  - 白名单模式：只解密名单内域名，其余透传
 * 两者都关闭 = 全局抓包
 */
object CaptureMode {
    private const val PREFS = "capture_mode"
    private const val KEY_BLACK_ON = "black_on"
    private const val KEY_WHITE_ON = "white_on"
    private const val KEY_QUIC_BLOCK = "quic_block"
    private const val KEY_HTTPS_PROXY = "https_proxy"
    private const val KEY_HTTPS_PROXY_INITIALIZED = "https_proxy_initialized"
    private const val KEY_BLACK = "black_list"
    private const val KEY_WHITE = "white_list"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blacklistOn(context: Context): Boolean = prefs(context).getBoolean(KEY_BLACK_ON, false)
    fun whitelistOn(context: Context): Boolean = prefs(context).getBoolean(KEY_WHITE_ON, false)

    /** 启用「QUIC 回退」：拦截 UDP 443，迫使 HTTP/3 流量降级为 HTTPS(HTTP/2) 以便解密。
     *  默认开启：否则 HTTP/3 接口会直接走 UDP 透传、永远抓不到（与代理类工具行为一致）。 */
    fun quicBlockOn(context: Context): Boolean = prefs(context).getBoolean(KEY_QUIC_BLOCK, true)

    /**
     * 启用「HTTPS 代理」（系统级 HTTP 代理，参考 ProxyPin）。
     * 开启后会在 VPN 接口上把整机 HTTP/HTTPS 系统代理指向本机 127.0.0.1:8888，
     * 从而能抓取 WebView / H5 等「不吃系统代理就直连 + 抢 QUIC」的流量。
     * 默认开启：与 ProxyPin 的 setSystemProxy=true 保持一致；用户仍可手动关闭。
     *
     * 注意：该开关在 VpnService 建立时读取，需在「开启抓包」时生效（与 ProxyPin 一致）。
     * 开启后会将系统代理连接送入本地 MITM；不信任本 CA 的 App 会在握手失败时断开，
     * 不会把已经开始的 TLS 会话错误地切换成透传。
     */
    fun httpsProxyOn(context: Context): Boolean {
        val p = prefs(context)
        // 早期版本曾以 false 写入该 key。首次运行新逻辑时迁移为 ProxyPin 的默认值 true，
        // 之后用户通过设置页主动关闭则保留 false。
        if (!p.getBoolean(KEY_HTTPS_PROXY_INITIALIZED, false)) {
            p.edit()
                .putBoolean(KEY_HTTPS_PROXY, true)
                .putBoolean(KEY_HTTPS_PROXY_INITIALIZED, true)
                .apply()
        }
        return p.getBoolean(KEY_HTTPS_PROXY, true)
    }

    fun setBlacklistOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLACK_ON, on).apply()

    fun setWhitelistOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_WHITE_ON, on).apply()

    fun setQuicBlockOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_QUIC_BLOCK, on).apply()

    fun setHttpsProxyOn(context: Context, on: Boolean) =
        prefs(context).edit()
            .putBoolean(KEY_HTTPS_PROXY, on)
            .putBoolean(KEY_HTTPS_PROXY_INITIALIZED, true)
            .apply()

    fun blacklist(context: Context): List<String> = load(context, KEY_BLACK)
    fun whitelist(context: Context): List<String> = load(context, KEY_WHITE)

    fun setBlacklist(context: Context, list: List<String>) = save(context, KEY_BLACK, list)
    fun setWhitelist(context: Context, list: List<String>) = save(context, KEY_WHITE, list)

    private fun load(context: Context, key: String): List<String> =
        (prefs(context).getString(key, "") ?: "")
            .split("\n", ",", " ", ";")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun save(context: Context, key: String, list: List<String>) =
        prefs(context).edit().putString(key, list.joinToString("\n")).apply()

    private fun matches(list: List<String>, host: String): Boolean {
        val h = host.lowercase()
        return list.any { d ->
            d == h || (d.startsWith("*.") && h.endsWith(d.substring(1))) ||
                (d.startsWith(".") && h.endsWith(d))
        }
    }

    /** 该 host 是否应当 MITM 解密（false = 透传不解析） */
    fun shouldMitm(context: Context, host: String): Boolean {
        if (whitelistOn(context)) return matches(whitelist(context), host)
        if (blacklistOn(context)) return !matches(blacklist(context), host)
        return true
    }
}
