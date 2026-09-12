package com.ht.streamdesk

import java.util.Locale

// MARK: - 资源类型（与手机端 ReqType / mac 版 ResourceType.swift 一致）

enum class ReqType(val label: String) {
    All("全部"),
    Xhr("Fetch/XHR"),
    Doc("文档"),
    Css("CSS"),
    Js("JS"),
    Img("图片"),
    Wasm("Wasm"),
    Other("其他");

    companion object {
        val filterable: List<ReqType> get() = entries.toList()
    }
}

/**
 * 按 Content-Type（响应优先）归类，缺失/不明确时按 path 扩展名兜底。
 * 抓包层无法感知页面发起类型（fetch/xhr/document），JSON/XML/表单等 API 响应归为 Fetch/XHR。
 */
fun classifyType(e: ExchangeRecord): ReqType {
    val raw = if (!e.respType.isNullOrEmpty()) e.respType!! else (e.reqType ?: "")
    val ct = raw.lowercase(Locale.ROOT).substringBefore(';').trim()
    val base = e.path.substringBefore('?').lowercase(Locale.ROOT)
    fun hasExt(vararg list: String) = list.any { base.endsWith(it) }

    if (ct.startsWith("image/")) return ReqType.Img
    if (ct.startsWith("application/wasm")) return ReqType.Wasm
    if (ct == "text/css") return ReqType.Css
    if (ct.contains("javascript") || ct.contains("ecmascript")) return ReqType.Js
    if (ct.contains("html") || ct == "application/xhtml+xml") return ReqType.Doc
    if (ct.contains("json") || ct.contains("xml") ||
        ct.contains("x-www-form-urlencoded") || ct.contains("multipart/form-data")
    ) return ReqType.Xhr

    if (hasExt(".css")) return ReqType.Css
    if (hasExt(".js", ".mjs", ".cjs")) return ReqType.Js
    if (hasExt(".html", ".htm")) return ReqType.Doc
    if (hasExt(".wasm")) return ReqType.Wasm
    if (hasExt(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp", ".avif", ".heic")) return ReqType.Img
    return ReqType.Other
}
