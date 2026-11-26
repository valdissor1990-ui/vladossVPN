package com.vpnproxy.di

import com.vpnproxy.core.doctor.AndroidDoctorProbes
import com.vpnproxy.core.doctor.ConnectionDoctor
import com.vpnproxy.core.doctor.DoctorProbes
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DoctorProbesModule {
    @Binds
    @Singleton
    abstract fun bindDoctorProbes(implementation: AndroidDoctorProbes): DoctorProbes

    companion object {
        /**
         * The orchestrator is stateless: one instance can serve every screen and test.
         * It is created from the interface, so the Android probes stay replaceable.
         */
        @Provides
        @Singleton
        fun provideConnectionDoctor(probes: DoctorProbes): ConnectionDoctor =
            ConnectionDoctor(probes)
    }
}
