package com.ht.stream.proxy

/** 从 TLS ClientHello 记录字节中提取 SNI */
object SniParser {

    /** [record] = 5 字节记录头 + 完整记录体 */
    fun extract(record: ByteArray): String? {
        return try {
            if (record.size < 5 || record[0].toInt() != 0x16) return null
            parseClientHello(record.copyOfRange(5, record.size))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseClientHello(b: ByteArray): String? {
        var p = 0
        if (b.size < 4 || b[0].toInt() != 1) return null // handshake type = client_hello
        p += 4 // type(1) + length(3)
        p += 2 // client version
        p += 32 // random
        if (p >= b.size) return null
        val sessionIdLen = b[p].toInt() and 0xFF; p += 1 + sessionIdLen
        if (p + 2 > b.size) return null
        val csLen = u16(b, p); p += 2 + csLen
        if (p >= b.size) return null
        val compLen = b[p].toInt() and 0xFF; p += 1 + compLen
        if (p + 2 > b.size) return null
        val extTotal = u16(b, p); p += 2
        val end = minOf(b.size, p + extTotal)
        while (p + 4 <= end) {
            val extType = u16(b, p)
            val extLen = u16(b, p + 2)
            p += 4
            if (p + extLen > end) return null
            if (extType == 0) { // server_name
                var q = p
                if (q + 2 > p + extLen) return null
                val listLen = u16(b, q); q += 2
                val listEnd = minOf(p + extLen, q + listLen)
                while (q + 3 <= listEnd) {
                    val nameType = b[q].toInt() and 0xFF
                    val nameLen = u16(b, q + 1)
                    q += 3
                    if (q + nameLen > listEnd) return null
                    if (nameType == 0) return String(b, q, nameLen, Charsets.US_ASCII)
                    q += nameLen
                }
                return null
            }
            p += extLen
        }
        return null
    }

    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}
