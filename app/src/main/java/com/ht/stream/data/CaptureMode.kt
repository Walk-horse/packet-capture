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
    private const val KEY_BLACK = "black_list"
    private const val KEY_WHITE = "white_list"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blacklistOn(context: Context): Boolean = prefs(context).getBoolean(KEY_BLACK_ON, false)
    fun whitelistOn(context: Context): Boolean = prefs(context).getBoolean(KEY_WHITE_ON, false)

    /** 启用「QUIC 回退」：拦截 UDP 443，迫使 HTTP/3 流量降级为 HTTPS(HTTP/2) 以便解密 */
    fun quicBlockOn(context: Context): Boolean = prefs(context).getBoolean(KEY_QUIC_BLOCK, false)

    fun setBlacklistOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLACK_ON, on).apply()

    fun setWhitelistOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_WHITE_ON, on).apply()

    fun setQuicBlockOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_QUIC_BLOCK, on).apply()

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
