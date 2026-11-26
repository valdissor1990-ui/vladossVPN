package com.vpnproxy.core.doctor

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Maps an app-layer exception to the fixed, secret-free [RemedyFailure] code.
 *
 * The mapping lives in a pure function on purpose: the Doctor core, the UI and a copied
 * report can only ever see a code, never the exception message. Messages from the engine and
 * from the subscription code routinely contain a server address, a subscription URL with a
 * token, or a profile name.
 */
fun mapRemedyFailure(error: Throwable): RemedyFailure = when (error) {
    is SocketTimeoutException, is TimeoutException -> RemedyFailure.TIMEOUT
    is UnknownHostException -> RemedyFailure.NETWORK
    is IOException -> RemedyFailure.NETWORK
    is IllegalStateException, is IllegalAccessException -> RemedyFailure.BAD_STATE
    else -> RemedyFailure.UNKNOWN
}
