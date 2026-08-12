package com.turbodm.app.download

import com.turbodm.app.download.handlers.MagnetSchemeHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MagnetSchemeHandlerTest {

    @Test fun `SchemeRegistry routes magnet URLs to MagnetSchemeHandler`() {
        val magnet = MagnetSchemeHandler()
        val r = SchemeRegistry(setOf(magnet))
        val resolved = r.handlerFor("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a77a")
        assertNotNull(resolved)
        assertTrue(resolved is MagnetSchemeHandler)
    }

    @Test fun `MagnetSchemeHandler declares magnet scheme`() {
        val h = MagnetSchemeHandler()
        assertEquals(setOf("magnet"), h.schemes)
    }

    @Test fun `magnet appears in supportedSchemes union`() {
        val magnet = MagnetSchemeHandler()
        val r = SchemeRegistry(setOf(magnet))
        assertTrue("magnet" in r.supportedSchemes)
    }

    @Test fun `MagnetSchemeHandler probe fails fast with explanatory message`() {
        val h = MagnetSchemeHandler()
        var thrown: Throwable? = null
        try {
            kotlinx.coroutines.runBlocking { h.probe("magnet:?xt=urn:btih:abc") }
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        assertTrue(
            "error message should point at addMagnet",
            (thrown?.message ?: "").contains("addMagnet")
        )
    }
}
