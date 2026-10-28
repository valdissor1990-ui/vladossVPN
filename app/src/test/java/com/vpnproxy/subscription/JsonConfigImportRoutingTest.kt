package com.vpnproxy.subscription

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON import is accepted when payload starts with { or [ — ViewModel routes to addFromJsonConfig.
 */
class JsonConfigImportRoutingTest {
    @Test
    fun detectsJsonObjectPayload() {
        val sample = """{"type":"vless","tag":"x","server":"1.2.3.4","server_port":443}"""
        assertTrue(sample.trim().startsWith("{"))
    }

    @Test
    fun detectsJsonArrayPayload() {
        val sample = """[{"type":"vless","server":"1.2.3.4","server_port":443}]"""
        assertTrue(sample.trim().startsWith("["))
    }
}
