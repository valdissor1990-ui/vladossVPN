package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for `TrojanUriParser.isDomainName` IPv4 matching. */
class TrojanUriParserHostTest {

    private fun warnings(uri: String): List<String> {
        val result = TrojanUriParser.parse(uri, "test")
        assertTrue("expected a successful parse for $uri, got $result", result is ParseResult.Success)
        return (result as ParseResult.Success).warnings
    }

    @Test
    fun ipv4LiteralHostWarnsAboutMissingSni() {
        val warnings = warnings("trojan://secret@1.2.3.4:443")
        assertTrue(
            "an IPv4 literal must not be treated as a domain",
            warnings.any { it.contains("not a domain") }
        )
    }

    @Test
    fun domainHostDoesNotWarnAboutMissingSni() {
        val warnings = warnings("trojan://secret@example.com:443")
        assertTrue(
            "a real domain name must not trigger the non-domain warning",
            warnings.none { it.contains("not a domain") }
        )
    }
}
