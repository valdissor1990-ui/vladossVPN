package com.vpnproxy.core.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodecTest {
    @Test
    fun subscriptionUrlRoundTripsThroughQr() {
        val url = "https://provider.example/sub?token=abc123&device=android"
        val image = QrCodec.encode(url, sizePx = 320)

        assertNotNull(image)
        assertEquals(320, image!!.size)
        assertEquals(url, QrCodec.decode(image.argb, image.size, image.size))
    }

    @Test
    fun singleConfigLinkRoundTripsThroughQr() {
        val link = "vless://11111111-1111-1111-1111-111111111111@proxy.example.org:443" +
            "?security=reality&sni=sni.example.org&type=tcp#Node"

        val image = QrCodec.encode(link, sizePx = 480)!!

        assertEquals(link, QrCodec.decode(image.argb, image.size, image.size))
    }

    @Test
    fun payloadsLargerThanQrCapacityAreRejectedInsteadOfThrowing() {
        assertNull(QrCodec.encode("x".repeat(QrCodec.MAX_PAYLOAD_CHARS + 1)))
        assertNull(QrCodec.encode("   "))
    }

    @Test
    fun blankPixelsDecodeToNull() {
        val blank = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        assertNull(QrCodec.decode(blank, 64, 64))
    }

    @Test
    fun invalidDimensionsDecodeToNull() {
        val pixels = IntArray(16)
        assertNull(QrCodec.decode(pixels, 0, 16))
        assertNull(QrCodec.decode(pixels, 16, 0))
        assertNull(QrCodec.decode(IntArray(4), 16, 16))
    }

    @Test
    fun sizeIsClampedToTheMinimum() {
        val image = QrCodec.encode("https://provider.example/sub", sizePx = 8)

        assertNotNull(image)
        assertTrue(image!!.size >= QrCodec.MIN_SIZE_PX)
    }
}
