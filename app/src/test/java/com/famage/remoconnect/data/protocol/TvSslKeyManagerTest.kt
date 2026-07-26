package com.famage.remoconnect.data.protocol

import org.junit.Assert.*
import org.junit.Test

class TvSslKeyManagerTest {

    @Test
    fun testGetOrCreateSslContextGeneratesMtlsContext() {
        val manager = TvSslKeyManager()
        val sslContext = manager.getOrCreateSslContext()

        assertNotNull("SSLContext should not be null", sslContext)
        assertEquals("TLS", sslContext.protocol)
    }

    @Test
    fun testCachedSslContextReused() {
        val manager = TvSslKeyManager()
        val context1 = manager.getOrCreateSslContext()
        val context2 = manager.getOrCreateSslContext()

        assertSame("Cached SSLContext should be reused across calls", context1, context2)
    }
}
