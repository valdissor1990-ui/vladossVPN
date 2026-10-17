package com.vpnproxy.core.ping

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PingExecutorModule {
    @Binds
    @Singleton
    abstract fun bindPingExecutor(implementation: RealPingExecutor): PingExecutor
}
