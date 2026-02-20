package com.tudorc.mediabus.server

import android.content.Context
import com.tudorc.mediabus.util.ServerLogger
import okhttp3.tls.HeldCertificate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLContext

object CertificateManager {
    private val keyPassword = "mediabus".toCharArray()

    fun createServerSocketFactory(
        appContext: Context,
        hostName: String = "mediabus.local",
    ): SSLServerSocketFactory {
        val keyStoreFile = File(File(appContext.filesDir, CERT_STORE_DIR), CERT_STORE_FILE)
        val keyStore = loadOrCreateKeyStore(keyStoreFile, hostName)

        val keyManagerFactory =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, keyPassword)
            }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())
        ServerLogger.i(LOG_COMPONENT, "TLS server socket factory ready")
        return sslContext.serverSocketFactory
    }

    private fun loadOrCreateKeyStore(
        keyStoreFile: File,
        hostName: String,
    ): KeyStore {
        val existing = loadKeyStoreIfValid(keyStoreFile)
        if (existing != null) {
            ServerLogger.i(LOG_COMPONENT, "Using persisted TLS certificate host=$hostName")
            return existing
        }

        ServerLogger.i(
            LOG_COMPONENT,
            "Generating self-signed TLS certificate host=$hostName validityDays=${CERTIFICATE_VALIDITY_DAYS}",
        )
        val now = System.currentTimeMillis()
        val certificate = HeldCertificate.Builder()
            .commonName(hostName)
            .addSubjectAlternativeName(hostName)
            .validityInterval(now, now + CERTIFICATE_VALIDITY_MS)
            .build()

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, keyPassword)
        keyStore.setKeyEntry(
            KEY_ALIAS,
            certificate.keyPair.private,
            keyPassword,
            arrayOf(certificate.certificate),
        )

        keyStoreFile.parentFile?.mkdirs()
        FileOutputStream(keyStoreFile).use { output ->
            keyStore.store(output, keyPassword)
        }
        ServerLogger.i(LOG_COMPONENT, "Persisted TLS certificate at ${keyStoreFile.absolutePath}")
        return keyStore
    }

    private fun loadKeyStoreIfValid(keyStoreFile: File): KeyStore? {
        if (!keyStoreFile.exists()) return null
        val loaded = runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            FileInputStream(keyStoreFile).use { input ->
                keyStore.load(input, keyPassword)
            }
            val certificate = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
            if (certificate == null) {
                ServerLogger.w(LOG_COMPONENT, "Persisted TLS certificate missing alias; regenerating")
                return@runCatching null
            }
            if (System.currentTimeMillis() >= certificate.notAfter.time) {
                ServerLogger.i(LOG_COMPONENT, "Persisted TLS certificate expired; regenerating")
                return@runCatching null
            }
            keyStore
        }.onFailure { throwable ->
            ServerLogger.w(
                LOG_COMPONENT,
                "Failed to load persisted TLS certificate: ${throwable.message}; regenerating",
            )
            runCatching { keyStoreFile.delete() }
        }.getOrNull()
        if (loaded == null) {
            runCatching { keyStoreFile.delete() }
        }
        return loaded
    }

    private const val CERTIFICATE_VALIDITY_DAYS = 3650L
    private const val CERTIFICATE_VALIDITY_MS = CERTIFICATE_VALIDITY_DAYS * 24 * 60 * 60 * 1000L
    private const val CERT_STORE_DIR = "tls"
    private const val CERT_STORE_FILE = "mediabus-server.p12"
    private const val KEYSTORE_TYPE = "PKCS12"
    private const val KEY_ALIAS = "mediabus"
    private const val LOG_COMPONENT = "Certificate"
}
