package com.example.camstream

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Самопідписаний TLS-сертифікат (RSA 2048), створюється один раз і зберігається
 * у приватній папці застосунку (PKCS12).
 */
object TlsHelper {
    private const val ALIAS = "camstream"
    private val PASS = "camstream-tls".toCharArray()
    private const val DAY = 24L * 3600 * 1000

    private fun file(ctx: Context) = File(ctx.filesDir, "tls.p12")

    private fun load(ctx: Context): KeyStore? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            f.inputStream().use { ks.load(it, PASS) }
            val c = ks.getCertificate(ALIAS) as? X509Certificate ?: return null
            if (c.notAfter.after(Date(System.currentTimeMillis() + 30 * DAY))) ks else null
        } catch (_: Throwable) { null }
    }

    private fun create(ctx: Context): KeyStore {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=CamStream")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()),
            Date(now - DAY), Date(now + 3650 * DAY), name, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, PASS, arrayOf(cert))
        file(ctx).outputStream().use { ks.store(it, PASS) }
        return ks
    }

    private fun keyStore(ctx: Context) = load(ctx) ?: create(ctx)

    fun sslFactory(ctx: Context): SSLServerSocketFactory {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(ctx), PASS)
        val sc = SSLContext.getInstance("TLS")
        sc.init(kmf.keyManagers, null, null)
        return sc.serverSocketFactory
    }

    /** SHA-256 відбиток сертифіката — для звірки у браузері. */
    fun fingerprint(ctx: Context): String? {
        val c = load(ctx)?.getCertificate(ALIAS) ?: return null
        return MessageDigest.getInstance("SHA-256").digest(c.encoded)
            .joinToString(":") { "%02X".format(it) }
    }
}
