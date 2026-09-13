package com.ht.stream.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条接口模拟规则（由桌面端「接口配置」下发，手机端只读执行）。
 *
 * 匹配维度：应用（按 uid）→ 规则开关 → method / host / path。
 * body 为空时表示「未配置示例」，由 MockEngine 按接口响应数据类型自动生成。
 */
data class MockRule(
    val id: String,
    val enabled: Boolean = true,
    val name: String = "",
    val method: String = "*",
    val host: String = "",
    val path: String = "",
    val statusCode: Int = 200,
    val contentType: String = "application/json",
    val body: String = "",
    val delayMs: Long = 0,
    val headers: List<Pair<String, String>> = emptyList(),
    /** 响应示例来源：桌面端从 YAPI 拉取时写入的接口 ID（手工/抓包生成的规则为空） */
    val yapiId: String? = null
) {
    /** 是否自定义了响应示例（否则按响应类型自动生成） */
    val hasCustomBody: Boolean get() = body.isNotBlank()

    /** 示例来源描述（与桌面端 MockRule.sourceText 口径一致） */
    val sourceText: String
        get() = if (!yapiId.isNullOrBlank()) "YAPI #$yapiId"
        else if (hasCustomBody) "自定义示例"
        else "自动生成示例"
}

/**
 * 接口模拟配置存储。
 *
 * - rules：桌面端下发，整体覆盖式写入
 * - enabledApps：手机端「接口模拟」设置页逐应用开关
 * - enabled：总开关
 *
 * 代理层每条请求都会查一次，故全部走内存缓存，写入时同步刷新。
 */
object MockStore {
    private const val PREFS = "mock_store"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_APPS = "apps"
    private const val KEY_RULES = "rules"
    private const val KEY_UPDATED = "rules_updated_at"

    @Volatile private var cachedRules: List<MockRule>? = null
    @Volatile private var cachedApps: Set<String>? = null
    private val uidPkgCache = ConcurrentHashMap<Int, String>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 总开关 ----------

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    @Synchronized
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    // ---------- 规则 ----------

    @Synchronized
    fun rules(context: Context): List<MockRule> {
        cachedRules?.let { return it }
        val raw = prefs(context).getString(KEY_RULES, "[]") ?: "[]"
        val out = parseRules(raw)
        cachedRules = out
        return out
    }

    fun rulesJson(context: Context): String =
        prefs(context).getString(KEY_RULES, "[]") ?: "[]"

    fun rulesUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_UPDATED, 0L)

    /** 桌面端下发：整体覆盖规则，返回写入的规则条数 */
    @Synchronized
    fun setRulesJson(context: Context, raw: String): Int {
        val list = parseRules(raw)
        prefs(context).edit()
            .putString(KEY_RULES, raw)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
        cachedRules = list
        return list.size
    }

    private fun parseRules(raw: String): List<MockRule> {
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<MockRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                MockRule(
                    id = o.optString("id").ifBlank { "rule-$i" },
                    enabled = o.optBoolean("enabled", true),
                    name = o.optString("name"),
                    method = o.optString("method", "*").ifBlank { "*" },
                    host = o.optString("host"),
                    path = o.optString("path"),
                    statusCode = o.optInt("statusCode", 200),
                    contentType = o.optString("contentType", "application/json").ifBlank { "application/json" },
                    body = o.optString("body"),
                    delayMs = o.optLong("delayMs", 0L),
                    headers = parseHeaders(o.optJSONArray("headers")),
                    yapiId = o.optString("yapiId").ifBlank { null }
                )
            )
        }
        return out
    }

    private fun parseHeaders(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<String, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val k = row.optString(0)
            val v = row.optString(1)
            if (k.isNotBlank()) out.add(k to v)
        }
        return out
    }

    // ---------- 按应用开关 ----------

    @Synchronized
    fun enabledApps(context: Context): Set<String> {
        cachedApps?.let { return it }
        val raw = prefs(context).getString(KEY_APPS, "[]") ?: "[]"
        val set = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapTo(LinkedHashSet()) { arr.optString(it) }
        }.getOrDefault(emptySet())
        cachedApps = set
        return set
    }

    fun isAppEnabled(context: Context, pkg: String): Boolean = pkg in enabledApps(context)

    @Synchronized
    fun setAppEnabled(context: Context, pkg: String, on: Boolean) {
        val set = enabledApps(context).toMutableSet()
        if (on) set.add(pkg) else set.remove(pkg)
        val arr = JSONArray().apply { set.forEach { put(it) } }
        prefs(context).edit().putString(KEY_APPS, arr.toString()).apply()
        cachedApps = set
    }

    fun clearApps(context: Context) {
        prefs(context).edit().remove(KEY_APPS).apply()
        cachedApps = emptySet()
    }

    /**
     * 清除本机接口模拟配置：规则 + 按应用开关 + 总开关（一并关掉）。
     *
     * 只动本机这份配置：抓包记录、已安装的 CA 证书都不受影响；
     * Mac 端本地规则也不受影响，重新「推送配置到手机」即可恢复。
     * 返回被清掉的数量，供 UI / `/api/mock/clear` 回执。
     */
    @Synchronized
    fun clearAll(context: Context): JSONObject {
        val ruleCount = rules(context).size
        val appCount = enabledApps(context).size
        prefs(context).edit()
            .remove(KEY_RULES)
            .remove(KEY_UPDATED)
            .remove(KEY_APPS)
            .putBoolean(KEY_ENABLED, false)
            .apply()
        cachedRules = emptyList()
        cachedApps = emptySet()
        return JSONObject().apply {
            put("ok", true)
            put("clearedRules", ruleCount)
            put("clearedApps", appCount)
            put("enabled", false)
        }
    }

    /** uid → 包名（代理层按 uid 判定该应用是否开启模拟） */
    fun packageOfUid(context: Context, uid: Int): String? {
        if (uid < 0) return null
        uidPkgCache[uid]?.let { return it }
        val pkg = runCatching {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
        if (pkg != null) uidPkgCache[uid] = pkg
        return pkg
    }

    /** 该 uid 对应应用是否启用了接口模拟 */
    fun isUidMocked(context: Context, uid: Int): Boolean {
        if (!isEnabled(context)) return false
        val pkg = packageOfUid(context, uid) ?: return false
        return isAppEnabled(context, pkg)
    }

    /** 用于同步给桌面端的状态摘要 */
    fun status(context: Context): JSONObject = JSONObject().apply {
        put("enabled", isEnabled(context))
        put("ruleCount", rules(context).size)
        put("enabledApps", JSONArray().apply { enabledApps(context).forEach { put(it) } })
        put("updatedAt", rulesUpdatedAt(context))
    }

    /** 把规则序列化回 JSON（供 GET /api/mock 回读，字段与桌面端一致） */
    fun rulesToJson(context: Context): JSONArray {
        val arr = JSONArray()
        rules(context).forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("enabled", r.enabled)
                put("name", r.name)
                put("method", r.method)
                put("host", r.host)
                put("path", r.path)
                put("statusCode", r.statusCode)
                put("contentType", r.contentType)
                put("body", r.body)
                put("delayMs", r.delayMs)
                put("headers", JSONArray().apply {
                    r.headers.forEach { (k, v) -> put(JSONArray().apply { put(k); put(v) }) }
                })
                r.yapiId?.takeIf { it.isNotBlank() }?.let { put("yapiId", it) }
            })
        }
        return arr
    }
}
