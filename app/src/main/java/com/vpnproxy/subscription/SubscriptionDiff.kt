package com.vpnproxy.subscription

import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig

/**
 * Privacy-safe diff between two subscription snapshots (audit item P1.4).
 *
 * Profiles are matched by a stable endpoint identity: provider tag, transport,
 * address, port and credential. When the identity is unchanged but connection
 * parameters differ (SNI, path, host, Reality key, flow, ...), the profile is
 * reported as [Kind.CHANGED] instead of a removal plus an addition.
 *
 * The result exposes only display names and counters. It intentionally never
 * contains raw URIs, UUIDs, passwords, keys or subscription URLs, so it can be
 * shown in the UI and logged without additional masking.
 */
object SubscriptionDiff {

    enum class Kind { ADDED, CHANGED, REMOVED }

    data class Entry(
        val kind: Kind,
        val providerTag: String,
        val displayName: String
    )

    data class Result(
        val added: List<Entry>,
        val changed: List<Entry>,
        val removed: List<Entry>,
        val unchangedCount: Int,
        val previousCount: Int,
        val currentCount: Int
    ) {
        val hasChanges: Boolean
            get() = added.isNotEmpty() || changed.isNotEmpty() || removed.isNotEmpty()

        /** First import has nothing to compare with; additions are not an "update". */
        val isFirstSnapshot: Boolean
            get() = previousCount == 0 && currentCount > 0
    }

    /**
     * Stable identity of a server entry. Credentials are part of the identity:
     * a rotated UUID/password is a different profile, not a changed one.
     */
    private data class Identity(
        val providerTag: String,
        val transport: String,
        val address: String,
        val port: Int,
        val credential: String
    )

    private fun identity(config: ParsedConfig): Identity = Identity(
        providerTag = config.providerTag,
        transport = config.transport.lowercase(java.util.Locale.ROOT),
        address = config.address.lowercase(java.util.Locale.ROOT),
        port = config.port,
        credential = config.uuid.ifBlank { config.password.orEmpty() }
    )

    /** Name is user-facing; fall back to the endpoint already visible in the server list. */
    private fun displayName(config: ParsedConfig): String =
        config.name.ifBlank { "${config.address}:${config.port}" }

    fun compute(previous: List<ParsedConfig>, current: List<ParsedConfig>): Result {
        // The repository already stores one entry per provider+connection key; mirror that
        // contract so a duplicated payload entry cannot look like a new profile.
        val previousProfiles = previous.distinctBy { it.providerTag + '\u001F' + it.connectionKey() }
        val currentProfiles = current.distinctBy { it.providerTag + '\u001F' + it.connectionKey() }

        val added = mutableListOf<Entry>()
        val changed = mutableListOf<Entry>()
        val removed = mutableListOf<Entry>()
        var unchanged = 0

        val previousByIdentity = previousProfiles.groupBy(::identity)
        val currentByIdentity = currentProfiles.groupBy(::identity)

        for (id in previousByIdentity.keys + currentByIdentity.keys) {
            val previousGroup = previousByIdentity[id].orEmpty()
            val currentGroup = currentByIdentity[id].orEmpty()
            if (previousGroup.isEmpty()) {
                currentGroup.forEach { added += Entry(Kind.ADDED, id.providerTag, displayName(it)) }
                continue
            }
            if (currentGroup.isEmpty()) {
                previousGroup.forEach { removed += Entry(Kind.REMOVED, id.providerTag, displayName(it)) }
                continue
            }

            // Match identical connection keys first; those profiles did not change.
            val remainingPrevious = previousGroup
                .groupBy { it.connectionKey() }
                .mapValues { it.value.toMutableList() }
                .toMutableMap()
            val unmatchedCurrent = mutableListOf<ParsedConfig>()
            currentGroup.forEach { profile ->
                val bucket = remainingPrevious[profile.connectionKey()]
                if (!bucket.isNullOrEmpty()) {
                    bucket.removeAt(bucket.lastIndex)
                    unchanged++
                } else {
                    unmatchedCurrent += profile
                }
            }
            val unmatchedPrevious = remainingPrevious.values.flatten()

            // Same endpoint and credentials but different parameters: pair as changed.
            val paired = minOf(unmatchedPrevious.size, unmatchedCurrent.size)
            repeat(paired) { index ->
                changed += Entry(Kind.CHANGED, id.providerTag, displayName(unmatchedCurrent[index]))
            }
            unmatchedCurrent.drop(paired).forEach {
                added += Entry(Kind.ADDED, id.providerTag, displayName(it))
            }
            unmatchedPrevious.drop(paired).forEach {
                removed += Entry(Kind.REMOVED, id.providerTag, displayName(it))
            }
        }

        val order = compareBy<Entry>({ it.providerTag }, { it.displayName })
        return Result(
            added = added.sortedWith(order),
            changed = changed.sortedWith(order),
            removed = removed.sortedWith(order),
            unchangedCount = unchanged,
            previousCount = previousProfiles.size,
            currentCount = currentProfiles.size
        )
    }
}
