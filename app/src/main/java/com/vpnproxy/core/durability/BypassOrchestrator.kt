package com.vpnproxy.core.durability

import com.vpnproxy.core.singbox.DurabilityOptions
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves the durability preset forward after a failed connect.
 *
 * It deliberately owns no retry loop, no timers and no storage: the caller asks for the next
 * preset once per failure, persists it and reconnects through `ConnectionCoordinator`, which
 * already owns retries and the fatal lock. The budget is [MAX_ATTEMPTS] preset changes per
 * connection attempt, so a dead network cannot spin forever.
 *
 * [onConnected] resets the budget; [resetBudget] does the same when the user manually starts
 * a fresh connection attempt.
 */
@Singleton
class BypassOrchestrator @Inject constructor() {

    companion object {
        /**
         * NORMAL -> HARD_NETWORK -> MAXIMUM_STEALTH is exactly two preset changes. Keeping
         * the budget equal to the ladder length means the limit is reachable and testable.
         */
        const val MAX_ATTEMPTS = 2
    }

    private val attempts = AtomicInteger(0)

    /**
     * @return the preset to persist next, or null when there is nothing stronger to try, the
     * current configuration is custom (never overwrite it silently), or the budget is used up.
     */
    fun nextProfile(current: DurabilityOptions): BypassProfile? {
        if (attempts.get() >= MAX_ATTEMPTS) return null
        val next = when (BypassProfile.detect(current)) {
            BypassProfile.NORMAL -> BypassProfile.HARD_NETWORK
            BypassProfile.HARD_NETWORK -> BypassProfile.MAXIMUM_STEALTH
            BypassProfile.MAXIMUM_STEALTH, BypassProfile.CUSTOM -> return null
        }
        attempts.incrementAndGet()
        return next
    }

    /** Clears the budget after a successful connection. */
    fun onConnected() = resetBudget()

    /** Clears the budget after an explicit user-initiated connect. */
    fun resetBudget() {
        attempts.set(0)
    }

    fun attemptsUsed(): Int = attempts.get()
}
