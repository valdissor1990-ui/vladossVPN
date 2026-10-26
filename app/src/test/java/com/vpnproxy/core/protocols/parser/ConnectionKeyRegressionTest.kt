package com.vpnproxy.core.protocols.parser

import com.vpnproxy.core.protocols.model.ParseResult
import com.vpnproxy.core.protocols.model.VlessConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConnectionKeyRegressionTest {

    private val tag = "test-provider"
    private val uuid = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `vless connectionKey equals server colon port colon uuid`() {
        val cases = listOf(
            "vless://$uuid@78.159.246.85:2053?type=xhttp&security=tls&sni=fitindoor.net&host=fitindoor.net&path=%2Fapi%2Fv1%2Fupload&mode=auto&fp=chrome#VK" to ("78.159.246.85" to 2053),
            "vless://$uuid@78.159.246.179:2053?type=xhttp&security=tls&sni=randomquaint.net&host=randomquaint.net&path=%2Fapi%2Fv1%2Fupload&mode=auto&fp=firefox#VK4" to ("78.159.246.179" to 2053),
            "vless://$uuid@188.72.103.4:443?type=xhttp&security=tls&sni=ru.nnight.cfd&host=ru.nnight.cfd&path=%2Fapi%2Fv4%2Fmedia%2Fsession%2Fpoll&mode=packet-up&fp=firefox#Selectel" to ("188.72.103.4" to 443),
            "vless://$uuid@45.12.75.242:26424?security=reality&sni=ya.ru&fp=qq&pbk=l8AubqcxQO-HRFJy4pZL1vbLOo-eXit69s-XulSELE0&sid=23103e1e#Beget" to ("45.12.75.242" to 26424),
            "vless://$uuid@188.225.38.216:443?type=grpc&security=tls&sni=lalibobi.online&fp=chrome#Poland" to ("188.225.38.216" to 443)
        )

        cases.forEach { (uri, ep) ->
            val result = VlessUriParser.parse(uri, tag)
            assertIs<ParseResult.Success<*>>(result)
            val config = assertIs<VlessConfig>(result.value)
            assertEquals(ep.first, config.server)
            assertEquals(ep.second, config.port)
            assertEquals(uuid, config.uuid)
            assertEquals("${ep.first}:${ep.second}:$uuid", config.connectionKey)
        }
    }

    @Test
    fun `different endpoints yield different keys`() {
        val a = assertIs<ParseResult.Success<*>>(
            VlessUriParser.parse("vless://$uuid@1.2.3.4:443", tag)
        ).value as VlessConfig
        val b = assertIs<ParseResult.Success<*>>(
            VlessUriParser.parse("vless://$uuid@5.6.7.8:443", tag)
        ).value as VlessConfig
        assertEquals("1.2.3.4:443:$uuid", a.connectionKey)
        assertEquals("5.6.7.8:443:$uuid", b.connectionKey)
    }
}
