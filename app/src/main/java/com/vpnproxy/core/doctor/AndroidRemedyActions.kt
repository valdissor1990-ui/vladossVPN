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
            val report = subscriptionRepository.lastImportReport.value
            val hasFreshProviderResponse = report?.providers?.any { provider ->
                provider.failureCode == null &&
                    provider.source != "encrypted_body" &&
                    provider.source != "aggregate_cache" &&
                    provider.source != "failed"
            } == true
            if (configs > 0 && hasFreshProviderResponse) {
                RemedyActionResult.Done
            } else {
                // refreshAll() can return cached profiles after every network request failed.
                // A positive profile count alone is not evidence that the subscription updated.
                RemedyActionResult.Failed(
                    if (configs == 0) RemedyFailure.REJECTED else RemedyFailure.NETWORK
                )
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
            // ACTION_ENSURE_RUNNING only logs the actor state; it does not start a
            // disconnected tunnel. Do not report a successful restart when there is no
            // active VPN service to reconnect.
            if (!VpnProxyService.isRunning.value) {
                return@withContext RemedyActionResult.Failed(RemedyFailure.BAD_STATE)
            }
            val intent = Intent(context, VpnProxyService::class.java).apply {
                action = VpnProxyService.ACTION_REQUEST_RECONNECT
            }
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
            if (!VpnProxyService.isRunning.value) {
                return@withContext RemedyActionResult.Failed(RemedyFailure.BAD_STATE)
            }
            val current = autoSwitcher.currentServer.value
                ?: return@withContext RemedyActionResult.Failed(RemedyFailure.REJECTED)
            val next = autoSwitcher.switchToNext()
                ?: return@withContext RemedyActionResult.Failed(RemedyFailure.REJECTED)
            if (next.config.connectionKey() == current.config.connectionKey()) {
                // AutoSwitcher returns the current server when it is the only healthy option.
                // Do not turn that no-op into a fake successful switch/reconnect.
                return@withContext RemedyActionResult.Failed(RemedyFailure.REJECTED)
            }
            // Match the normal manual-selection path: reset retry state before selecting.
            connectionCoordinator.send(ConnectionEvent.ResetRetryCount)
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
