package com.ht.stream.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 本地 Hosts 映射：域名 → IP/域名。抓包开启时代理层生效。 */
object HostsStore {
    private const val PREFS = "hosts_store"
    private const val KEY = "mappings"

    data class Mapping(val domain: String, val target: String)

    @Volatile private var cached: List<Mapping>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(context: Context): List<Mapping> {
        cached?.let { return it }
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<Mapping>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Mapping(o.optString("domain"), o.optString("target")))
        }
        cached = out
        return out
    }

    @Synchronized
    fun add(context: Context, domain: String, target: String) {
        val list = list(context).filterNot { it.domain.equals(domain, true) } +
            Mapping(domain.trim().lowercase(), target.trim())
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, domain: String) {
        save(context, list(context).filterNot { it.domain.equals(domain, true) })
    }

    private fun save(context: Context, list: List<Mapping>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("domain", it.domain).put("target", it.target)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        cached = list
    }

    /**
     * 命中映射则返回目标地址（IP 或域名），否则 null。
     * 支持精确匹配与前缀通配（*.example.com 或 .example.com 匹配子域）。
     */
    fun resolve(context: Context, host: String): String? {
        val h = host.lowercase()
        for (m in list(context)) {
            val d = m.domain
            when {
                d == h -> return m.target
                d.startsWith("*.") && h.endsWith(d.substring(1)) -> return m.target
                d.startsWith(".") && h.endsWith(d) -> return m.target
            }
        }
        return null
    }

    /** 导出为 hosts 文件文本 */
    fun exportText(context: Context): String =
        list(context).joinToString("\n") { "${it.target} ${it.domain}" }
}
