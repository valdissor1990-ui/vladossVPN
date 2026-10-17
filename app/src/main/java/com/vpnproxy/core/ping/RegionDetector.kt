package com.vpnproxy.core.ping

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegionDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun detect(): PingRegion = PingTargets.detectRegion(context)
}
