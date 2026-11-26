package com.vpnproxy.di

import com.vpnproxy.core.connection.ConnectionCoordinator
import com.vpnproxy.core.connection.ConnectionState
import com.vpnproxy.core.doctor.AndroidRemedyActions
import com.vpnproxy.core.doctor.RemedyActions
import com.vpnproxy.core.doctor.RemedyExecutor
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DoctorActionsModule {

    @Binds
    @Singleton
    abstract fun bindRemedyActions(implementation: AndroidRemedyActions): RemedyActions

    companion object {
        /**
         * One executor per app: its mutex is what makes a double tap return BUSY instead of
         * starting two restarts.
         *
         * The restart guard is evaluated at press time. A restart must not run while the
         * tunnel is being established: stopping the core then would turn a slow connect into
         * a user-visible failure.
         */
        @Provides
        @Singleton
        fun provideRemedyExecutor(
            actions: RemedyActions,
            connectionCoordinator: ConnectionCoordinator,
            logCollector: LogCollector
        ): RemedyExecutor {
            val isRestartAllowed: () -> Boolean = {
                val state = connectionCoordinator.state.value
                val connecting = state is ConnectionState.Connecting || state is ConnectionState.Verifying
                !connecting && !VpnProxyService.isCoreTransitioning.value
            }
            return RemedyExecutor(
                actions = actions,
                isRestartAllowed = isRestartAllowed,
                log = { line -> logCollector.warn("RemedyExecutor", line) }
            )
        }
    }
}
