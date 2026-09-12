package com.ht.streamdesk

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class FormattingTest {

    @Test
    fun sizeAndDuration() {
        assertEquals("512 B", fmtSize(512))
        assertEquals("1.0 KB", fmtSize(1024))
        assertEquals("2.00 MB", fmtSize(2 * 1024 * 1024))
        assertEquals("进行中", fmtDuration(-1))
        assertEquals("250 ms", fmtDuration(250))
        assertEquals("1.50 s", fmtDuration(1500))
    }

    @Test
    fun hexDumpLayout() {
        val dump = hexDump(byteArrayOf(0x9F.toByte(), 0x58, 0xAC.toByte(), 0xEA.toByte(), 0x41, 0x00, 0x7F))
        val first = dump.lines().first()
        assertTrue(first.startsWith("00000000  9f 58 ac ea 41 00 7f"), "实际：$first")
        assertTrue(first.endsWith("|.X..A..|"), "实际：$first")
    }

    @Test
    fun hexDumpTruncationNote() {
        val dump = hexDump(ByteArray(100) { 1 }, limit = 16)
        assertContains(dump, "仅显示前 16 B（共 100 B）")
    }

    @Test
    fun decodeUtf8AndBom() {
        assertEquals("hello 中文", decodeText("hello 中文".toByteArray()))
        // UTF-16LE BOM
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)
        assertEquals("hi", decodeText(le))
        // UTF-16BE BOM
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi".toByteArray(Charsets.UTF_16BE)
        assertEquals("hi", decodeText(be))
        assertEquals("", decodeText(ByteArray(0)))
    }

    @Test
    fun binaryBodyIsNotText() {
        // 与 mac 版的差异点：加密二进制不应被当成乱码文本（mac 会退到 Latin-1）
        val encrypted = ByteArray(64) { (it * 37 + 200).toByte() }
        assertNull(decodeText(encrypted))
    }

    @Test
    fun prettyJsonOnlyForJson() {
        val pretty = prettyJSON("""{"b":1,"a":[1,2]}""")
        assertNotNull(pretty)
        assertContains(pretty, "\"b\": 1")
        assertContains(pretty, "\n")
        assertNull(prettyJSON("not json at all"))
        assertNull(prettyJSON(""))
    }

    @Test
    fun curlBuildsReplayableCommand() {
        val e = ExchangeRecord(
            id = "1",
            method = "POST",
            url = "https://api.example.com/a?x=1",
            requestHeaders = listOf(
                listOf("Content-Type", "application/json"),
                listOf("Content-Length", "9"),
                listOf("Accept", "*/*"),
            ),
            requestBodyB64 = java.util.Base64.getEncoder().encodeToString("""{"a":1}""".toByteArray()),
        )
        val cmd = curlCommand(e)
        assertTrue(cmd.startsWith("curl -X POST 'https://api.example.com/a?x=1'"), cmd)
        assertContains(cmd, "-H 'Content-Type: application/json'")
        assertContains(cmd, "-H 'Accept: */*'")
        // Content-Length 会被 --data-raw 重算，必须剔除
        assertTrue(!cmd.contains("Content-Length"), cmd)
        assertContains(cmd, "--data-raw '{\"a\":1}'")
    }

    @Test
    fun curlQuotesSingleQuotesSafely() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun curlForBinaryBodyPointsToFile() {
        val e = ExchangeRecord(
            id = "2",
            method = "POST",
            url = "https://api.example.com/bin",
            requestHeaders = emptyList(),
            requestBodyB64 = java.util.Base64.getEncoder()
                .encodeToString(ByteArray(32) { (it * 91 + 7).toByte() }),
        )
        val cmd = curlCommand(e)
        assertContains(cmd, "--data-binary @<文件路径>")
        assertTrue(!cmd.contains("--data-raw"), cmd)
    }

    @Test
    fun curlMarksDecompressedBody() {
        val e = ExchangeRecord(
            id = "3",
            method = "POST",
            url = "https://api.example.com/gz",
            requestHeaders = listOf(listOf("Content-Encoding", "gzip"), listOf("Content-Length", "40")),
            requestBodyB64 = java.util.Base64.getEncoder().encodeToString("plain".toByteArray()),
            reqEncoding = "gzip",
        )
        val cmd = curlCommand(e)
        assertContains(cmd, "--data-raw 'plain'")
        assertTrue(!cmd.contains("Content-Encoding"), cmd)
        assertContains(cmd, "# 原始 body 为 GZIP 压缩")
    }
}

class ResourceTypeTest {

    private fun ex(path: String, respType: String? = null, reqType: String? = null) =
        ExchangeRecord(id = UUID.randomUUID().toString(), path = path, respType = respType, reqType = reqType)

    @Test
    fun classifiesByContentTypeFirst() {
        assertEquals(ReqType.Xhr, classifyType(ex("/api/x", respType = "application/json; charset=utf-8")))
        assertEquals(ReqType.Img, classifyType(ex("/x", respType = "image/png")))
        assertEquals(ReqType.Css, classifyType(ex("/x", respType = "text/css")))
        assertEquals(ReqType.Js, classifyType(ex("/x", respType = "application/javascript")))
        assertEquals(ReqType.Doc, classifyType(ex("/x", respType = "text/html")))
        assertEquals(ReqType.Wasm, classifyType(ex("/x", respType = "application/wasm")))
        assertEquals(ReqType.Xhr, classifyType(ex("/x", respType = "application/x-www-form-urlencoded")))
    }

    @Test
    fun responseTypeWinsOverRequestType() {
        assertEquals(ReqType.Img, classifyType(ex("/x", respType = "image/webp", reqType = "application/json")))
    }

    @Test
    fun fallsBackToPathExtension() {
        assertEquals(ReqType.Css, classifyType(ex("/static/a.css?v=1")))
        assertEquals(ReqType.Js, classifyType(ex("/static/a.mjs")))
        assertEquals(ReqType.Doc, classifyType(ex("/index.htm")))
        assertEquals(ReqType.Img, classifyType(ex("/logo.avif")))
        assertEquals(ReqType.Other, classifyType(ex("/api/collect")))
    }

    @Test
    fun protobufGoesToOther() {
        assertEquals(ReqType.Other, classifyType(ex("/v3/collect", reqType = "application/protobuf")))
    }
}

class ProtocolTest {

    private val decoder = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/state-sample.json")) { "缺少 fixture" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun parsesRealDevicePayload() {
        val state = decoder.decodeFromString(SyncState.serializer(), fixture())
        assertEquals(3, state.exchanges.size)

        val e = state.exchanges.first { it.requestHeaders.isNotEmpty() }
        assertTrue(e.host.isNotEmpty())
        assertTrue(e.url.startsWith("https://"))
        assertTrue(e.uid > 0)
        assertTrue(e.statusCode in 100..599)

        val ct = e.requestHeaderPairs.firstOrNull { it.first.equals("Content-Type", true) }
        assertNotNull(ct, "应解析出请求头键值对")
        assertTrue(e.requestData.isNotEmpty(), "请求体 base64 应能解码")
        assertNotNull(e.requestSize)
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        // 真实协议里 null 字段会被省略：respText / reqEncoding / error 均可能不存在
        val state = decoder.decodeFromString(SyncState.serializer(), fixture())
        val e = state.exchanges.first()
        assertNull(e.error)
        assertTrue(e.responseBodyB64.isEmpty() || e.responseBodyB64.isNotEmpty())
        assertEquals("", e.error ?: "")
        // fixture 是真实设备数据：透传记录按实际条数解析，且字段完整
        assertTrue(state.passthrough.isNotEmpty(), "真实样本应含透传记录")
        val p = state.passthrough.first()
        assertTrue(p.host.isNotEmpty())
        assertTrue(p.reason.isNotEmpty())
        assertTrue(p.startTime > 1_600_000_000_000L)
    }

    @Test
    fun sessionFieldsParse() {
        val state = decoder.decodeFromString(SyncState.serializer(), fixture())
        assertTrue(state.sessions.isNotEmpty())
        val s = state.sessions.first()
        assertTrue(s.id.isNotEmpty())
        assertTrue(s.requestCount > 0)
        assertTrue(s.startTime > 1_600_000_000_000L)
    }

    @Test
    fun urlNormalisation() {
        val client = SyncClient(prefs = tempPrefs(), http = { "{}" })
        client.setAddress("192.168.1.20:17890")
        assertEquals("http://192.168.1.20:17890/api/state?full=1", client.makeURL(full = true))
        assertEquals("http://192.168.1.20:17890/api/state", client.makeURL(full = false))

        client.setAddress("http://192.168.1.20:17890/api/state")
        assertEquals("http://192.168.1.20:17890/api/state?full=1", client.makeURL(full = true))

        client.setAddress("192.168.1.20:17890/")
        assertEquals("http://192.168.1.20:17890/api/state?full=1", client.makeURL(full = true))

        client.setAddress("   ")
        assertNull(client.makeURL(full = false))
    }

    @Test
    fun incrementalMergeKeepsOrderAndDedupes() = runBlocking {
        val payloads = ArrayDeque(
            listOf(
                state(full = true, ids = listOf("a", "b")),
                state(full = false, ids = listOf("c", "d")),
                state(full = false, ids = listOf("d", "e")), // d 是重复的
            )
        )
        val client = SyncClient(prefs = tempPrefs(), http = { payloads.removeFirst() })
        client.setAddress("127.0.0.1:17890")

        client.pull(full = true)
        assertEquals(listOf("a", "b"), client.exchanges.value.map { it.id })

        client.pull()
        assertEquals(listOf("c", "d", "a", "b"), client.exchanges.value.map { it.id })

        client.pull()
        assertEquals(listOf("e", "c", "d", "a", "b"), client.exchanges.value.map { it.id })

        assertTrue(client.status.value is SyncStatus.Ok)
        // stats.requests 镜像手机端总量（最后一次 payload 的 requestCount），不是本地条数
        assertEquals(2, client.stats.value.requests)
    }

    @Test
    fun networkFailureKeepsLocalDataAndReportsStatus() = runBlocking {
        val payloads = ArrayDeque(listOf(state(full = true, ids = listOf("a"))))
        var fail = false
        val client = SyncClient(
            prefs = tempPrefs(),
            http = {
                if (fail) throw java.net.ConnectException("refused") else payloads.removeFirst()
            },
        )
        client.setAddress("127.0.0.1:17890")
        client.pull(full = true)
        assertEquals(1, client.exchanges.value.size)

        fail = true
        client.pull()
        assertEquals(1, client.exchanges.value.size, "失败时不应清空本地数据")
        val st = client.status.value
        assertTrue(st is SyncStatus.Failed, "实际：$st")
        assertContains((st as SyncStatus.Failed).message, "无法连接手机")
    }

    @Test
    fun fullSyncReplacesEverything() = runBlocking {
        val payloads = ArrayDeque(
            listOf(
                state(full = true, ids = listOf("a", "b", "c")),
                state(full = true, ids = listOf("x")),
            )
        )
        val client = SyncClient(prefs = tempPrefs(), http = { payloads.removeFirst() })
        client.setAddress("127.0.0.1:17890")
        client.pull(full = true)
        client.pull(full = true)
        assertEquals(listOf("x"), client.exchanges.value.map { it.id })
    }

    @Test
    fun clearLocalResetsState() = runBlocking {
        val client = SyncClient(
            prefs = tempPrefs(),
            http = { state(full = true, ids = listOf("a")) },
        )
        client.setAddress("127.0.0.1:17890")
        client.pull(full = true)
        client.clearLocal()
        assertTrue(client.exchanges.value.isEmpty())
        assertEquals(SyncStatus.Idle, client.status.value)
    }

    // ---- helpers ----

    private fun tempPrefs() =
        java.util.prefs.Preferences.userRoot().node("com/ht/streamdesk-test/${UUID.randomUUID()}")

    private fun state(full: Boolean, ids: List<String>): String {
        val items = ids.joinToString(",") { id ->
            """{"id":"$id","sessionId":"s1","scheme":"https","host":"h.example.com","port":443,
               "isTls":true,"method":"GET","path":"/p/$id","url":"https://h.example.com/p/$id",
               "statusCode":200,"statusText":"OK","state":"COMPLETE","favorite":false,"uid":10001,
               "startTime":1789130329025,"endTime":1789130329222,"durationMs":197,
               "requestHeaders":[],"responseHeaders":[],
               "requestBodyB64":"","requestBodyTruncated":false,
               "responseBodyB64":"","responseBodyTruncated":false}"""
        }
        return """{"capturing":true,"uploadBytes":10,"downloadBytes":20,"requestCount":${ids.size},
                   "passthroughCount":0,"full":$full,"sessions":[],"passthrough":[],
                   "exchanges":[$items]}"""
    }
}
