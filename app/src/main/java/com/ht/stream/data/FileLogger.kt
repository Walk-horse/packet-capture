package com.ht.stream.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 抓包日志：每次抓包一个文件，落在 filesDir/logs/ */
object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_FILES = 30

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val nameFmt = SimpleDateFormat("yyyy-MM-dd--HH-mm-ss", Locale.getDefault())

    @Volatile private var writer: FileWriter? = null
    @Volatile private var currentFile: File? = null

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "logs").apply { mkdirs() }

    @Synchronized
    fun start(context: Context) {
        stop()
        val f = File(dir(context), "packetcapture.${nameFmt.format(Date())}.log")
        try {
            writer = FileWriter(f, true)
            currentFile = f
            log("=== capture started ===")
            trimOld(context)
        } catch (e: Exception) {
            Log.w(TAG, "start: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        log("=== capture stopped ===")
        runCatching { writer?.close() }
        writer = null
        currentFile = null
    }

    @Synchronized
    fun log(msg: String) {
        val w = writer ?: return
        try {
            w.append("${timeFmt.format(Date())} $msg\n")
            w.flush()
        } catch (_: Exception) {}
    }

    fun listFiles(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun trimOld(context: Context) {
        val files = listFiles(context)
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    fun deleteAll(context: Context) {
        listFiles(context).forEach { it.delete() }
    }
}
