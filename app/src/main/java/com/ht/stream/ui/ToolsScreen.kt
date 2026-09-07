package com.ht.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import android.util.Base64 as AndroidBase64

private enum class Tool(val title: String, val note: String) {
    URL_CODEC("URL 编码解码", "URL 编码和解码"),
    BASE64("Base64 加密解密", "BASE64 加密和解密"),
    MD5("MD5", "MD5"),
    TIMESTAMP("时间戳转化", "时间戳转化"),
    RSA("RSA 加密解密", "RSA 加密和解密")
}

/** 常用工具：URL / Base64 / MD5 / 时间戳 / RSA */
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    var current by remember { mutableStateOf<Tool?>(null) }
    val tool = current
    if (tool == null) {
        Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
            StreamTopBar(title = "常用工具", onBack = onBack)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Tool.entries.forEach { t ->
                    SectionHeader(t.note)
                    Group {
                        CellRow(t.title, showChevron = true, onClick = { current = t }, showDivider = false)
                    }
                }
            }
        }
    } else {
        ToolPage(tool, onBack = { current = null })
    }
}

@Composable
private fun ToolPage(tool: Tool, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(StreamColors.BgGray)) {
        StreamTopBar(title = tool.title, onBack = onBack, backLabel = "常用工具")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            when (tool) {
                Tool.URL_CODEC -> CodecTool(
                    encode = { URLEncoder.encode(it, "UTF-8") },
                    decode = { URLDecoder.decode(it, "UTF-8") }
                )
                Tool.BASE64 -> CodecTool(
                    encode = { AndroidBase64.encodeToString(it.toByteArray(Charsets.UTF_8), AndroidBase64.NO_WRAP) },
                    decode = { String(AndroidBase64.decode(it.trim(), AndroidBase64.DEFAULT), Charsets.UTF_8) }
                )
                Tool.MD5 -> Md5Tool()
                Tool.TIMESTAMP -> TimestampTool()
                Tool.RSA -> RsaTool()
            }
        }
    }
}

@Composable
private fun CodecTool(encode: (String) -> String, decode: (String) -> String) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    ToolInput(input) { input = it }
    RowButtons(
        listOf("编码" to {
            output = runCatching { encode(input) }.getOrElse { "失败：${it.message}" }
        }, "解码" to {
            output = runCatching { decode(input) }.getOrElse { "失败：${it.message}" }
        })
    )
    ToolOutput(output)
}

@Composable
private fun Md5Tool() {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    ToolInput(input) { input = it }
    RowButtons(listOf("计算 MD5" to {
        output = runCatching {
            MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }.getOrElse { "失败：${it.message}" }
    }))
    ToolOutput(output)
}

@Composable
private fun TimestampTool() {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    // 展示/解析主格式不带毫秒；毫秒输入做兜底，未填时毫秒默认为 0
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val fmtMs = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    ToolInput(input, hint = "时间戳(秒/毫秒) 或 yyyy-MM-dd HH:mm:ss") { input = it }
    RowButtons(listOf(
        "转日期" to {
            output = runCatching {
                val n = input.trim().toLong()
                val ms = if (n < 10_000_000_000L) n * 1000 else n
                fmt.format(Date(ms))
            }.getOrElse { "失败：${it.message}" }
        },
        "转时间戳" to {
            output = runCatching {
                val d = fmt.parse(input.trim())
                    ?: fmtMs.parse(input.trim())
                    ?: throw IllegalArgumentException("无法解析日期")
                "${d.time}（毫秒）\n${d.time / 1000}（秒）"
            }.getOrElse { "失败：${it.message}" }
        },
        "当前时间" to {
            val now = System.currentTimeMillis()
            output = "${fmt.format(Date(now))}\n$now（毫秒）\n${now / 1000}（秒）"
        }
    ))
    ToolOutput(output)
}

@Composable
private fun RsaTool() {
    var publicKey by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }

    Text("密钥（Base64）", fontSize = 13.sp)
    OutlinedTextField(
        value = publicKey, onValueChange = { publicKey = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("公钥", fontSize = 12.sp) }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = privateKey, onValueChange = { privateKey = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("私钥", fontSize = 12.sp) }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    )
    Spacer(Modifier.height(6.dp))
    RowButtons(listOf("生成密钥对" to {
        runCatching {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            publicKey = AndroidBase64.encodeToString(kp.public.encoded, AndroidBase64.NO_WRAP)
            privateKey = AndroidBase64.encodeToString(kp.private.encoded, AndroidBase64.NO_WRAP)
            output = "已生成 RSA-2048 密钥对"
        }.onFailure { output = "失败：${it.message}" }
    }))
    Spacer(Modifier.height(12.dp))
    ToolInput(input) { input = it }
    RowButtons(listOf(
        "公钥加密" to {
            output = runCatching {
                val spec = java.security.spec.X509EncodedKeySpec(AndroidBase64.decode(publicKey.trim(), AndroidBase64.DEFAULT))
                val key = java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
                val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                c.init(Cipher.ENCRYPT_MODE, key)
                AndroidBase64.encodeToString(c.doFinal(input.toByteArray(Charsets.UTF_8)), AndroidBase64.NO_WRAP)
            }.getOrElse { "失败：${it.message}" }
        },
        "私钥解密" to {
            output = runCatching {
                val spec = java.security.spec.PKCS8EncodedKeySpec(AndroidBase64.decode(privateKey.trim(), AndroidBase64.DEFAULT))
                val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(spec)
                val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                c.init(Cipher.DECRYPT_MODE, key)
                String(c.doFinal(AndroidBase64.decode(input.trim(), AndroidBase64.DEFAULT)), Charsets.UTF_8)
            }.getOrElse { "失败：${it.message}" }
        }
    ))
    ToolOutput(output)
}

@Composable
private fun ToolInput(value: String, hint: String = "输入内容", onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().height(120.dp),
        placeholder = { Text(hint, fontSize = 13.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RowButtons(buttons: List<Pair<String, () -> Unit>>) {
    Row {
        buttons.forEachIndexed { i, (label, action) ->
            if (i > 0) Spacer(Modifier.padding(4.dp))
            Button(onClick = action) { Text(label, fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToolOutput(output: String) {
    Text("结果（长按可复制）", fontSize = 13.sp)
    Spacer(Modifier.height(4.dp))
    SelectionContainer {
        Text(
            output.ifEmpty { "-" },
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(12.dp)
        )
    }
}
