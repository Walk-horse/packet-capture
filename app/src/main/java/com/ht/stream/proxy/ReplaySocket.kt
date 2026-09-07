package com.ht.stream.proxy

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * 在已消费的 [prefix] 字节之上委托底层 socket 的包装。
 * 用于先嗅探（SNI / HTTP method）再分层 SSLSocket 的场景，
 * 避免预读字节被 SSLSocket 漏读。
 */
class ReplaySocket(
    private val inner: Socket,
    prefix: ByteArray
) : Socket() {
    private val replayIn: InputStream = SequenceInputStream(ByteArrayInputStream(prefix), inner.getInputStream())

    override fun getInputStream(): InputStream = replayIn
    override fun getOutputStream(): OutputStream = inner.getOutputStream()
    override fun close() = inner.close()
    override fun isClosed(): Boolean = inner.isClosed
    override fun isConnected(): Boolean = inner.isConnected
    override fun getInetAddress(): InetAddress = inner.inetAddress
    override fun getPort(): Int = inner.port
    override fun getLocalPort(): Int = inner.localPort
    override fun getLocalAddress(): InetAddress = inner.localAddress
    override fun setSoTimeout(timeout: Int) { inner.soTimeout = timeout }
    override fun getSoTimeout(): Int = inner.soTimeout
    override fun setTcpNoDelay(on: Boolean) { inner.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = inner.tcpNoDelay
    override fun setKeepAlive(on: Boolean) { inner.keepAlive = on }
    override fun shutdownInput() = inner.shutdownInput()
    override fun shutdownOutput() = inner.shutdownOutput()
    override fun connect(endpoint: SocketAddress?) = Unit
    override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
}
