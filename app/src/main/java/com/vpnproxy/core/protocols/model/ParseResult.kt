package com.vpnproxy.core.protocols.model

sealed class ParseResult<out T> {

    data class Success<T>(
        val value: T,
        val warnings: List<String> = emptyList()
    ) : ParseResult<T>()

    data class Unsupported(
        val reason: String
    ) : ParseResult<Nothing>()

    data class Skip(
        val reason: String
    ) : ParseResult<Nothing>()
}
