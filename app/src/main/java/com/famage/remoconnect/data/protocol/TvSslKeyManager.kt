package com.famage.remoconnect.data.protocol

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TvSslKeyManager(
    context: Context? = null
) {

    private var cachedSslContext: SSLContext? = null
    private var clientCertificate: X509Certificate? = null
    private val appContext = context?.applicationContext
    private val keyStoreFile: File? = appContext?.let { File(it.filesDir, "remoconnect_tls_identity.bks") }
    private val keyStorePassword = "remoconnect_pass".toCharArray()

    @Synchronized
    fun getOrCreateSslContext(): SSLContext {
        cachedSslContext?.let { return it }

        return try {
            val keyStore = loadOrCreateIdentityKeyStore()
            val cert = keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
                ?: throw IllegalStateException("Client certificate missing from identity keystore")

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, keyStorePassword)

            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, trustAllCerts, SecureRandom())
            clientCertificate = cert
            cachedSslContext = sslContext
            sslContext
        } catch (e: Exception) {
            Log.e("TvSslKeyManager", "Failed to initialize mTLS SSLContext", e)
            createFallbackSslContext()
        }
    }

    fun getClientCertificate(): X509Certificate? {
        getOrCreateSslContext()
        return clientCertificate
    }

    private fun loadOrCreateIdentityKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        val file = keyStoreFile

        if (file != null && file.exists()) {
            FileInputStream(file).use { input ->
                keyStore.load(input, keyStorePassword)
            }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return keyStore
            }
        } else {
            keyStore.load(null, null)
        }

        val keyPair = generateRsaKeyPair()
        val cert = generateSelfSignedCertificate(keyPair)
        keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, keyStorePassword, arrayOf(cert))

        if (file != null) {
            FileOutputStream(file).use { output ->
                keyStore.store(output, keyStorePassword)
            }
            Log.d("TvSslKeyManager", "Created persistent mTLS client identity at ${file.absolutePath}")
        }

        return keyStore
    }

    private fun generateRsaKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        return keyGen.generateKeyPair()
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val subjectName = "CN=RemoConnect, O=RemoConnect, OU=MobileRemote"
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date(System.currentTimeMillis() - 86400000L)
        val notAfter = Date(System.currentTimeMillis() + 315360000000L)

        val certBytes = buildSelfSignedX509Der(subjectName, serialNumber, notBefore, notAfter, keyPair)
        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }

    private fun buildSelfSignedX509Der(
        subject: String,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        keyPair: KeyPair
    ): ByteArray {
        val pubKeyBytes = keyPair.public.encoded
        val sigEngine = java.security.Signature.getInstance("SHA256withRSA")
        sigEngine.initSign(keyPair.private)

        val tbsDer = encodeTbsCertificate(subject, serial, notBefore, notAfter, pubKeyBytes)
        sigEngine.update(tbsDer)
        val signature = sigEngine.sign()

        val sha256withRsaOid = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00
        )
        val sigBitString = encodeBitString(signature)

        val certContent = tbsDer + sha256withRsaOid + sigBitString
        return encodeSequence(certContent)
    }

    private fun encodeTbsCertificate(
        subject: String,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        pubKeyBytes: ByteArray
    ): ByteArray {
        val version = byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02) // v3
        val serialDer = encodeInteger(serial)
        val sigAlgOid = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09,
            0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00
        )
        val issuerDer = encodeName("CN=RemoConnect")
        val validityDer = encodeValidity(notBefore, notAfter)
        val subjectDer = encodeName(subject)

        val tbsContent = version + serialDer + sigAlgOid + issuerDer + validityDer + subjectDer + pubKeyBytes
        return encodeSequence(tbsContent)
    }

    private fun encodeName(name: String): ByteArray {
        val cnBytes = name.toByteArray(Charsets.UTF_8)
        val attrValue = byteArrayOf(0x0C, cnBytes.size.toByte()) + cnBytes
        val attrOid = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val attrSeq = encodeSequence(attrOid + attrValue)
        val setSeq = encodeSet(attrSeq)
        return encodeSequence(setSeq)
    }

    private fun encodeValidity(notBefore: Date, notAfter: Date): ByteArray {
        val format = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")

        val nbBytes = format.format(notBefore).toByteArray(Charsets.US_ASCII)
        val naBytes = format.format(notAfter).toByteArray(Charsets.US_ASCII)

        val nbTime = byteArrayOf(0x17, nbBytes.size.toByte()) + nbBytes
        val naTime = byteArrayOf(0x17, naBytes.size.toByte()) + naBytes

        return encodeSequence(nbTime + naTime)
    }

    private fun encodeSequence(content: ByteArray): ByteArray {
        return byteArrayOf(0x30) + encodeLength(content.size) + content
    }

    private fun encodeSet(content: ByteArray): ByteArray {
        return byteArrayOf(0x31) + encodeLength(content.size) + content
    }

    private fun encodeInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(0x02) + encodeLength(bytes.size) + bytes
    }

    private fun encodeBitString(bytes: ByteArray): ByteArray {
        val content = byteArrayOf(0x00) + bytes
        return byteArrayOf(0x03) + encodeLength(content.size) + content
    }

    private fun encodeLength(length: Int): ByteArray {
        return if (length < 128) {
            byteArrayOf(length.toByte())
        } else if (length < 256) {
            byteArrayOf(0x81.toByte(), length.toByte())
        } else {
            byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        }
    }

    private fun createFallbackSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext
    }

    private companion object {
        private const val KEY_ALIAS = "remoconnect_client"
    }
}
