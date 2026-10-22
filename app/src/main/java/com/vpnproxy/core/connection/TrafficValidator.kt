package com.vpnproxy.core.connection

import com.vpnproxy.core.singbox.SingboxConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates actual traffic through the active Sing-box outbound.
 *
 * The app package is excluded from Android's VPN route to prevent routing loops, so a plain
 * HttpURLConnection would bypass the tunnel and test the underlying network instead. Connect
 * explicitly to Sing-box's local mixed inbound; it routes the request through the selected proxy.
 */
@Singleton
class TrafficValidator @Inject constructor() {
    suspend fun checkTraffic(): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val localProxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", SingboxConfigBuilder.PROXY_PORT)
            )
            connection = (URL("https://cp.cloudflare.com/generate_204")
                .openConnection(localProxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                instanceFollowRedirects = false
                useCaches = false
            }
            val status = connection.responseCode
            status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_NO_CONTENT
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
