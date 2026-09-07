package com.ht.stream.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.KeyChain
import android.widget.Toast
import com.ht.stream.proxy.CertAuthority
import java.io.File

/** CA 证书安装：优先系统安装器，失败可导出到下载目录手动安装 */
object CertInstall {

    /** 调起系统证书安装器（DER 字节） */
    fun installViaSystem(activity: Activity) {
        val der = CertAuthority.caDerBytes(activity.applicationContext)
        if (der == null) {
            Toast.makeText(activity, "CA 生成失败", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = KeyChain.createInstallIntent().apply {
                putExtra(KeyChain.EXTRA_CERTIFICATE, der)
                // 部分 ROM 不读 EXTRA_NAME，多塞几个常见 key，避免名称显示 null
                putExtra(KeyChain.EXTRA_NAME, "Packet Capture")
                putExtra("CERT_NAME", "Packet Capture")
                putExtra("name", "Packet Capture")
            }
            // 必须用 startActivityForResult：MIUI 等 ROM 靠 callingPackage 解析
            // 调用方 App 名（「由 xx 提供的证书」），普通 startActivity 拿不到 → 显示 null
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, 0)
        } catch (e: Exception) {
            Toast.makeText(activity, "系统安装器不可用，请用「导出到下载目录」", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 导出 .crt 到下载目录，返回展示用路径；失败返回 null。
     * 用户可在文件管理器中点击该文件，或到 设置→安全→安装证书 手动选择。
     */
    fun exportToDownloads(context: Context): String? {
        val der = CertAuthority.caDerBytes(context.applicationContext) ?: return null
        val fileName = "PacketCaptureCA.crt"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(der) } ?: return null
                "Download/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, fileName)
                file.writeBytes(der)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
