package com.ht.stream.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
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
 * - rules：桌面端下发，按 host + path 增量合并
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

    data class RuleMergeResult(
        val total: Int,
        val added: Int,
        val updated: Int
    )

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

    /** 兼容旧调用：整体替换规则。桌面端同步请使用 [mergeRulesJson]。 */
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

    /**
     * 增量合并桌面端下发的规则：host + path 相同则更新，不同则追加。
     * method 不参与合并键，避免同一接口因请求方法差异产生重复配置。
     */
    @Synchronized
    fun mergeRulesJson(context: Context, raw: String): RuleMergeResult {
        val incoming = parseRules(raw)
        val current = rules(context).toMutableList()
        var added = 0
        var updated = 0
        incoming.forEach { rule ->
            val index = current.indexOfFirst { sameEndpoint(it, rule) }
            if (index >= 0) {
                // 保留手机端已有 id，避免桌面端规则 id 变化导致详情页引用失效。
                current[index] = rule.copy(id = current[index].id)
                updated++
            } else {
                current.add(rule)
                added++
            }
        }
        persistRules(context, current)
        return RuleMergeResult(current.size, added, updated)
    }

    /**
     * 从抓包详情新增或更新一条接口模拟规则。
     * 同一 method + host + path 只保留一条，避免重复点击产生重复规则。
     */
    @Synchronized
    fun upsertRule(context: Context, rule: MockRule): Boolean {
        val current = rules(context).toMutableList()
        val index = current.indexOfFirst { sameEndpoint(it, rule) }
        val stored = if (index >= 0) rule.copy(id = current[index].id) else rule.copy(id = UUID.randomUUID().toString())
        if (index >= 0) current[index] = stored else current.add(stored)
        val raw = rulesToJson(current).toString()
        prefs(context).edit()
            .putString(KEY_RULES, raw)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
        cachedRules = current.toList()
        return index >= 0
    }

    /** 删除一条接口模拟规则。 */
    @Synchronized
    fun removeRule(context: Context, ruleId: String): Boolean {
        val current = rules(context).toMutableList()
        val removed = current.removeAll { it.id == ruleId }
        if (!removed) return false
        persistRules(context, current)
        return true
    }

    private fun sameEndpoint(a: MockRule, b: MockRule): Boolean =
        a.host.trim().equals(b.host.trim(), ignoreCase = true) && a.path == b.path

    private fun persistRules(context: Context, rules: List<MockRule>) {
        val raw = rulesToJson(rules).toString()
        prefs(context).edit()
            .putString(KEY_RULES, raw)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
        cachedRules = rules.toList()
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
    fun rulesToJson(context: Context): JSONArray = rulesToJson(rules(context))

    private fun rulesToJson(rules: List<MockRule>): JSONArray {
        val arr = JSONArray()
        rules.forEach { r ->
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
