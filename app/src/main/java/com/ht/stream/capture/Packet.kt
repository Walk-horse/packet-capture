package com.ht.stream.capture

import java.net.InetAddress
import java.nio.ByteBuffer

/** IPv4/TCP/UDP 报文解析与构造工具 */
object Packet {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    data class IpHeader(
        val headerLen: Int,
        val totalLen: Int,
        val protocol: Int,
        val src: ByteArray,   // 4 bytes
        val dst: ByteArray
    )

    data class TcpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val window: Int,
        val headerLen: Int,
        val payloadOffset: Int, // 相对报文起始
        val payloadLen: Int
    )

    fun parseIp(buf: ByteArray, len: Int): IpHeader? {
        if (len < 20) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl) return null
        val totalLen = readU16(buf, 2)
        if (totalLen < ihl || totalLen > len) return null
        return IpHeader(
            headerLen = ihl,
            totalLen = totalLen,
            protocol = buf[9].toInt() and 0xFF,
            src = buf.copyOfRange(12, 16),
            dst = buf.copyOfRange(16, 20)
        )
    }

    fun parseTcp(buf: ByteArray, ip: IpHeader): TcpSegment? {
        if (ip.totalLen < ip.headerLen + 20) return null
        val off = ip.headerLen
        val dataOffset = ((buf[off + 12].toInt() shr 4) and 0xF) * 4
        if (dataOffset < 20) return null
        val payloadOffset = off + dataOffset
        if (payloadOffset > ip.totalLen) return null
        return TcpSegment(
            srcPort = readU16(buf, off),
            dstPort = readU16(buf, off + 2),
            seq = readU32(buf, off + 4),
            ack = readU32(buf, off + 8),
            flags = buf[off + 13].toInt() and 0x3F,
            window = readU16(buf, off + 14),
            headerLen = dataOffset,
            payloadOffset = payloadOffset,
            payloadLen = ip.totalLen - payloadOffset
        )
    }

    /**
     * 构造一个 IPv4 + TCP 报文（用于回写 TUN）。
     * 方向：从 [srcIp]:[srcPort]（对端/服务器）发往 [dstIp]:[dstPort]（本机 App）。
     */
    fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        payloadOffset: Int = 0,
        payloadLen: Int = payload.size,
        window: Int = 65535,
        withMssOption: Boolean = false
    ): ByteArray {
        val tcpHeaderLen = if (withMssOption) 24 else 20
        val totalLen = 20 + tcpHeaderLen + payloadLen
        val out = ByteBuffer.allocate(totalLen)

        // IPv4 header
        out.put(0x45.toByte())
        out.put(0)                       // TOS
        out.putShort(totalLen.toShort())
        out.putShort(0)                  // ID
        out.putShort(0x4000.toShort())   // DF
        out.put(64.toByte())             // TTL
        out.put(PROTO_TCP.toByte())
        out.putShort(0)                  // checksum placeholder
        out.put(srcIp)
        out.put(dstIp)
        val ipChecksum = checksum(out.array(), 0, 20)
        out.putShort(10, ipChecksum.toShort())

        // TCP header
        out.putShort(srcPort.toShort())
        out.putShort(dstPort.toShort())
        out.putInt(seq.toInt())
        out.putInt(ack.toInt())
        out.put(((tcpHeaderLen / 4) shl 4).toByte())
        out.put(flags.toByte())
        out.putShort(window.toShort())
        out.putShort(0) // checksum placeholder
        out.putShort(0) // urgent
        if (withMssOption) {
            out.put(0x02.toByte()); out.put(0x04.toByte()); out.putShort(1460.toShort())
        }
        if (payloadLen > 0) out.put(payload, payloadOffset, payloadLen)

        // TCP checksum（伪首部）
        val arr = out.array()
        val pseudo = ByteBuffer.allocate(12 + tcpHeaderLen + payloadLen)
        pseudo.put(srcIp); pseudo.put(dstIp)
        pseudo.put(0); pseudo.put(PROTO_TCP.toByte())
        pseudo.putShort((tcpHeaderLen + payloadLen).toShort())
        pseudo.put(arr, 20, tcpHeaderLen + payloadLen)
        val tcpSum = checksum(pseudo.array(), 0, pseudo.array().size)
        out.putShort(20 + 16, tcpSum.toShort())
        return arr
    }

    /** 构造 IPv4 + UDP 报文 */
    fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, payloadOffset: Int = 0, payloadLen: Int = payload.size
    ): ByteArray {
        val totalLen = 20 + 8 + payloadLen
        val out = ByteBuffer.allocate(totalLen)
        out.put(0x45.toByte()); out.put(0)
        out.putShort(totalLen.toShort())
        out.putShort(0); out.putShort(0x4000.toShort())
        out.put(64.toByte()); out.put(PROTO_UDP.toByte()); out.putShort(0)
        out.put(srcIp); out.put(dstIp)
        out.putShort(10, checksum(out.array(), 0, 20).toShort())

        out.putShort(srcPort.toShort()); out.putShort(dstPort.toShort())
        out.putShort((8 + payloadLen).toShort()); out.putShort(0)
        if (payloadLen > 0) out.put(payload, payloadOffset, payloadLen)

        val arr = out.array()
        val pseudo = ByteBuffer.allocate(12 + 8 + payloadLen)
        pseudo.put(srcIp); pseudo.put(dstIp)
        pseudo.put(0); pseudo.put(PROTO_UDP.toByte())
        pseudo.putShort((8 + payloadLen).toShort())
        pseudo.put(arr, 20, 8 + payloadLen)
        out.putShort(20 + 6, checksum(pseudo.array(), 0, pseudo.array().size).toShort())
        return arr
    }

    fun parseUdp(buf: ByteArray, ip: IpHeader): UdpDatagram? {
        if (ip.totalLen < ip.headerLen + 8) return null
        val off = ip.headerLen
        val len = readU16(buf, off + 4)
        val payloadLen = minOf(len - 8, ip.totalLen - off - 8)
        if (payloadLen < 0) return null
        return UdpDatagram(
            srcPort = readU16(buf, off),
            dstPort = readU16(buf, off + 2),
            payloadOffset = off + 8,
            payloadLen = payloadLen
        )
    }

    data class UdpDatagram(val srcPort: Int, val dstPort: Int, val payloadOffset: Int, val payloadLen: Int)

    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    fun readU16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    fun readU32(b: ByteArray, off: Int) =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    fun ipToString(ip: ByteArray): String = InetAddress.getByAddress(ip).hostAddress ?: "?"
    fun ipKey(ip: ByteArray): String = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
}
