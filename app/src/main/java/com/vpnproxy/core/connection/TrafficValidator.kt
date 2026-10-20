package com.vpnproxy.core.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates actual traffic through the active Android VPN route.
 * A successful Sing-box start alone is not evidence of usable internet access.
 */
@Singleton
class TrafficValidator @Inject constructor() {
    suspend fun checkTraffic(): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("http://cp.cloudflare.com/generate_204").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2_000
                readTimeout = 2_000
                instanceFollowRedirects = false
                useCaches = false
            }
            val status = connection.responseCode
            status == HttpURLConnection.HTTP_OK || status == 204
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
