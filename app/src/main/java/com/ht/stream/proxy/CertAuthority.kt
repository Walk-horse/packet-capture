package com.ht.stream.proxy

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 本地 CA 管理：首次启动生成自签 CA（持久化到 filesDir），
 * 按需为目标域名动态签发站点证书（内存缓存）。
 *
 * 注意：Android 7+ 的目标 App 默认不信任用户 CA，HTTPS 解密只对
 * 「信任用户证书」的 App 生效（或本 App 自身，已在 networkSecurityConfig 放行）。
 */
object CertAuthority {
    private const val TAG = "CertAuthority"
    private const val CA_CN = "Packet capture Local CA"
    private const val KEYSTORE_PASSWORD = "androidstream"

    @Volatile private var caCert: X509Certificate? = null
    @Volatile private var caKey: PrivateKey? = null
    private val contextCache = ConcurrentHashMap<String, SSLContext>()

    /** 清除站点证书缓存（MITM 缓存） */
    fun clearCache() {
        contextCache.clear()
    }

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Synchronized
    fun ensureCa(context: Context): Boolean {
        if (caCert != null && caKey != null) return true
        val certFile = File(context.filesDir, "packetcapture_ca.pem")
        val keyFile = File(context.filesDir, "packetcapture_ca_key.pem")
        return try {
            if (certFile.exists() && keyFile.exists()) {
                loadCa(certFile, keyFile)
            } else {
                generateCa(certFile, keyFile)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureCa failed", e)
            false
        }
    }

    private fun loadCa(certFile: File, keyFile: File) {
        val cf = CertificateFactory.getInstance("X.509")
        caCert = certFile.inputStream().use { cf.generateCertificate(it) as X509Certificate }
        val pemText = keyFile.readText()
        val base64 = pemText
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keySpec = java.security.spec.PKCS8EncodedKeySpec(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        caKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun generateCa(certFile: File, keyFile: File) {
        val kpGen = KeyPairGenerator.getInstance("RSA")
        kpGen.initialize(2048, SecureRandom())
        val kp = kpGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val name = X500Name("CN=$CA_CN")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(160, SecureRandom()),
            Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L),
            name, kp.public
        )
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.digitalSignature or KeyUsage.cRLSign)
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(kp.public))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        caCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        caKey = kp.private

        certFile.writeText(toPem(caCert!!))
        keyFile.writeText(toPkcs8Pem(kp.private))
        Log.i(TAG, "CA generated: ${certFile.absolutePath}")
    }

    /** 供安装引导使用的 CA 证书 PEM 字节 */
    fun caPemBytes(context: Context): ByteArray? {
        if (!ensureCa(context)) return null
        return File(context.filesDir, "packetcapture_ca.pem").readBytes()
    }

    /** 供 KeyChain 安装意图 / 导出 .crt 使用的 DER 字节 */
    fun caDerBytes(context: Context): ByteArray? {
        if (!ensureCa(context)) return null
        return try { caCert?.encoded } catch (e: Exception) {
            Log.e(TAG, "encode ca failed", e)
            null
        }
    }

    fun caCertificate(context: Context): X509Certificate? {
        if (!ensureCa(context)) return null
        return caCert
    }

    /** 指定 host 的服务端 SSLContext（动态签发的站点证书 + CA 链） */
    fun serverContext(context: Context, host: String): SSLContext? {
        if (!ensureCa(context)) return null
        return contextCache.getOrPut(host) {
            try {
                buildServerContext(host)
            } catch (e: Exception) {
                Log.e(TAG, "sign cert for $host failed", e)
                throw e
            }
        }
    }

    private fun buildServerContext(host: String): SSLContext {
        val issuer = caCert ?: error("CA not ready")
        val issuerKey = caKey ?: error("CA not ready")

        val kpGen = KeyPairGenerator.getInstance("RSA")
        kpGen.initialize(2048, SecureRandom())
        val kp = kpGen.generateKeyPair()

        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger(160, SecureRandom()),
            Date(now - 86_400_000L), Date(now + 825L * 86_400_000L),
            X500Name("CN=$host"), kp.public
        )
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(kp.public))
        builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(issuer))
        builder.addExtension(
            Extension.extendedKeyUsage, false,
            org.bouncycastle.asn1.x509.ExtendedKeyUsage(org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth)
        )
        val sanType = if (host.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) GeneralName.iPAddress else GeneralName.dNSName
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(sanType, host)))

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKey)
        val siteCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("site", kp.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(siteCert, issuer))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    private fun toPem(cert: X509Certificate): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(cert) }
        return sw.toString()
    }

    private fun toPkcs8Pem(key: PrivateKey): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(JcaPKCS8Generator(key, null).generate()) }
        return sw.toString()
    }
}
