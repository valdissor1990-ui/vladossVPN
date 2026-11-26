package com.vpnproxy.core.doctor

import android.content.Context
import android.content.Intent
import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionEvent
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-layer implementation of [RemedyActions].
 *
 * It reuses exactly the code the other screens use: the subscription repository refreshes the
 * providers, the auto-switcher picks the next healthy profile, and the service intent restarts
 * the core. The Doctor never reaches into these classes directly.
 *
 * Failures are returned as [RemedyFailure] codes. The exception class may be logged; the
 * message is not, because it can contain a URL with a token or a server address.
 */
@Singleton
class AndroidRemedyActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: SubscriptionRepository,
    private val autoSwitcher: AutoSwitcher,
    private val connectionCoordinator: ConnectionCoordinator,
    private val logCollector: LogCollector
) : RemedyActions {

    private companion object {
        const val TAG = "RemedyActions"
    }

    override suspend fun refreshSubscription(): RemedyActionResult = withContext(Dispatchers.IO) {
        try {
            val configs = subscriptionRepository.refreshAll()
            if (configs > 0) {
                RemedyActionResult.Done
            } else {
                // Refresh completed but produced nothing usable: report a rejection rather
                // than a success the user cannot see any effect from.
                RemedyActionResult.Failed(RemedyFailure.REJECTED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logCollector.warn(TAG, "refreshSubscription failed: ${error.javaClass.simpleName}")
            RemedyActionResult.Failed(mapRemedyFailure(error))
        }
    }

    override suspend fun restartCore(): RemedyActionResult = withContext(Dispatchers.Main) {
        try {
            // Restarting an active tunnel and starting an idle core are two different service
            // actions; the coordinator decides the rest.
            val action = if (VpnProxyService.isRunning.value) {
                VpnProxyService.ACTION_REQUEST_RECONNECT
            } else {
                VpnProxyService.ACTION_ENSURE_RUNNING
            }
            val intent = Intent(context, VpnProxyService::class.java).apply { this.action = action }
            context.startForegroundService(intent)
            RemedyActionResult.Done
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logCollector.warn(TAG, "restartCore failed: ${error.javaClass.simpleName}")
            RemedyActionResult.Failed(mapRemedyFailure(error))
        }
    }

    override suspend fun switchServer(): RemedyActionResult = withContext(Dispatchers.IO) {
        try {
            val next = autoSwitcher.switchToNext()
                ?: return@withContext RemedyActionResult.Failed(RemedyFailure.REJECTED)
            // The same path an explicit tap on a server row takes, so teardown and preflight
            // stay identical.
            connectionCoordinator.send(
                ConnectionEvent.ServerSelected(next, connectionCoordinator.activeTraceId)
            )
            RemedyActionResult.Done
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logCollector.warn(TAG, "switchServer failed: ${error.javaClass.simpleName}")
            RemedyActionResult.Failed(mapRemedyFailure(error))
        }
    }

    /**
     * Re-importing a single profile needs a repository API that fetches one provider and
     * replaces only its configs. Until it exists the executor must say "unsupported" rather
     * than quietly refresh every subscription under a different label.
     */
    override suspend fun reimportProfile(): RemedyActionResult = RemedyActionResult.Unsupported
}
