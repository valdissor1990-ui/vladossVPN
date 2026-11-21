package com.vpnproxy.subscription

import com.vpnproxy.logging.LogCollector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the truthy handling of `allowinsecure` / `insecure` in trojan URIs. */
class ExtraUriParsersTrojanTest {

    private val logCollector = LogCollector()

    private fun parse(query: String) = ExtraUriParsers.parseTrojan(
        uri = "trojan://secret@example.com:443?$query#node",
        providerTag = "test",
        logCollector = logCollector
    )

    @Test
    fun allowInsecureTruthyValuesEnableInsecure() {
        for (value in listOf("1", "true", "TRUE", "yes", "on")) {
            val config = parse("allowinsecure=$value")
            assertNotNull("allowinsecure=$value should parse", config)
            assertTrue("allowinsecure=$value should mark insecure", config!!.insecure)
        }
    }

    @Test
    fun insecureKeyAlsoAcceptsTruthyValues() {
        val config = parse("insecure=true")
        assertNotNull(config)
        assertTrue(config!!.insecure)
    }

    @Test
    fun falseValuesKeepTlsVerificationEnabled() {
        for (value in listOf("0", "false", "")) {
            val config = parse("allowinsecure=$value")
            assertNotNull("allowinsecure=$value should parse", config)
            assertFalse("allowinsecure=$value must not mark insecure", config!!.insecure)
        }
    }
}
