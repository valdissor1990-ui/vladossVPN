package com.vpnproxy.ui.services

import com.vpnproxy.core.services.ServiceCatalog
import com.vpnproxy.core.services.ServiceCheckStatus
import com.vpnproxy.core.services.ServiceChecker
import com.vpnproxy.core.services.ServiceProbe
import com.vpnproxy.core.services.ServiceProbeResult
import com.vpnproxy.core.services.ServiceTarget
import com.vpnproxy.logging.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val alwaysOk = object : ServiceProbe {
        override suspend fun probe(target: ServiceTarget) =
            ServiceProbeResult(reachable = true, latencyMs = 5L, httpCode = 200)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(probe: ServiceProbe = alwaysOk) =
        ServicesViewModel(LogCollector(), ServiceChecker(probe))

    @Test
    fun sweepWithoutVpnFinishesAndReleasesTheRunLock() = runTest(dispatcher) {
        val vm = viewModel()

        vm.checkAll()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse("isChecking must be cleared after the sweep", state.isChecking)
        // No VPN in the unit test: every service is reported as VPN_OFF.
        assertEquals(ServiceCatalog.defaultTargets.size, state.results.size)
        assertTrue(state.results.values.all { it.status == ServiceCheckStatus.VPN_OFF })
    }

    @Test
    fun secondTapWhileRunningReportsBusyInsteadOfSilentlyDoingNothing() = runTest(dispatcher) {
        val vm = viewModel()

        vm.checkAll()
        assertTrue(vm.state.value.isChecking)
        vm.checkAll()

        assertEquals("Проверка уже идёт.", vm.state.value.message)

        advanceUntilIdle()
        assertFalse(vm.state.value.isChecking)
    }

    @Test
    fun cancelReleasesTheRunLockSoTheUserCanRetry() = runTest(dispatcher) {
        val vm = viewModel()

        vm.checkAll()
        assertTrue(vm.state.value.isChecking)
        vm.cancel()
        advanceUntilIdle()

        assertFalse("cancelled sweep must not keep the lock", vm.state.value.isChecking)

        vm.checkAll()
        assertTrue(vm.state.value.isChecking)
        advanceUntilIdle()
        assertFalse(vm.state.value.isChecking)
        // The retry must produce a full table, not inherit a half-finished one.
        assertEquals(ServiceCatalog.defaultTargets.size, vm.state.value.results.size)
        assertFalse(vm.state.value.partial)
    }
}
