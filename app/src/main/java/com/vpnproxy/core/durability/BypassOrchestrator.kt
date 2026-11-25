package com.vpnproxy.core.durability

import android.content.Context
import com.vpnproxy.core.singbox.DurabilityOptions
import com.vpnproxy.core.singbox.DurabilitySettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves the durability preset forward after a failed connect.
 *
 * It deliberately owns no retry loop and no timers: the caller (HomeViewModel) asks for the
 * next preset once per failure, and the actual reconnect stays with `ConnectionCoordinator`,
 * which already owns retries and the fatal lock. The budget is [MAX_ATTEMPTS] preset changes
 * per connection attempt, so a dead network cannot spin forever.
 *
 * [onConnected] resets the budget; call it from the Connected branch.
 */
@Singleton
class BypassOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val MAX_ATTEMPTS = 4
    }

    private val attempts = AtomicInteger(0)

    /**
     * @return the preset that was saved, or null when there is nothing stronger to try or the
     * budget is exhausted.
     */
    fun nextProfile(current: DurabilityOptions): BypassProfile? {
        if (attempts.get() >= MAX_ATTEMPTS) return null
        val next = when (BypassProfile.detect(current)) {
            BypassProfile.NORMAL -> BypassProfile.HARD_NETWORK
            BypassProfile.HARD_NETWORK -> BypassProfile.MAXIMUM_STEALTH
            BypassProfile.MAXIMUM_STEALTH -> return null
        }
        attempts.incrementAndGet()
        DurabilitySettings.save(context, next.applyTo(current))
        return next
    }

    /** Clears the budget after a successful connection. */
    fun onConnected() {
        attempts.set(0)
    }

    fun attemptsUsed(): Int = attempts.get()
}
