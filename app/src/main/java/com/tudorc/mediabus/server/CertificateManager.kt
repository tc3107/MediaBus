package com.tudorc.mediabus.server

import com.tudorc.mediabus.util.ServerLogger
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLContext

object CertificateManager {
    private val keyPassword = "mediabus".toCharArray()

    fun createServerSocketFactory(
        ipAddress: InetAddress,
        hostName: String = "mediabus.local",
    ): SSLServerSocketFactory {
        val address = ipAddress.hostAddress ?: error("Missing host address")
        ServerLogger.i(
            LOG_COMPONENT,
            "Generating self-signed TLS certificate host=$hostName sanIp=$address validityHours=24",
        )
        val now = System.currentTimeMillis()
        val certificate = HeldCertificate.Builder()
            .commonName(hostName)
            .addSubjectAlternativeName(hostName)
            .addSubjectAlternativeName(address)
            .validityInterval(now, now + CERTIFICATE_VALIDITY_MS)
            .build()

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        keyStore.setKeyEntry(
            "mediabus",
            certificate.keyPair.private,
            keyPassword,
            arrayOf(certificate.certificate),
        )

        val keyManagerFactory =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, keyPassword)
            }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())
        ServerLogger.i(LOG_COMPONENT, "TLS server socket factory ready")
        return sslContext.serverSocketFactory
    }

    private const val CERTIFICATE_VALIDITY_MS = 24 * 60 * 60 * 1000L
    private const val LOG_COMPONENT = "Certificate"
}
