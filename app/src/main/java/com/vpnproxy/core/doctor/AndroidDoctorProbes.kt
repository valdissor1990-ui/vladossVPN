package com.vpnproxy.core.doctor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vpnproxy.core.connection.LastServerStore
import com.vpnproxy.core.services.ServiceCatalog
import com.vpnproxy.core.services.ServiceCheckStatus
import com.vpnproxy.core.services.ServiceChecker
import com.vpnproxy.core.singbox.DiagnosticProbe
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.DeepProbeEngine
import com.vpnproxy.subscription.JsonConfigRepository
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real probes behind the connection Doctor.
 *
 * Safety rules:
 * - results never contain addresses, SNI, UUIDs, subscription names or URLs;
 * - the journal receives only step status and an aggregate, never probe payloads;
 * - steps that need a stopped core take no action on their own: the orchestrator has
 *   already checked `DoctorContext` and the user's consent;
 * - a missing answer is `WARN`, not `FAIL`. The Doctor must not blame the network for a
 *   check it could not perform.
 */
@Singleton
class AndroidDoctorProbes @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptions: SubscriptionRepository,
    private val jsonConfigs: JsonConfigRepository,
    private val lastServerStore: LastServerStore,
    private val deepProbeEngine: DeepProbeEngine,
    private val serviceChecker: ServiceChecker,
    private val logCollector: LogCollector
) : DoctorProbes {

    private companion object {
        const val TAG = "DoctorProbes"
        const val TCP_TIMEOUT_MS = 3_000
        const val TLS_TIMEOUT_MS = 5_000
        const val DNS_TIMEOUT_MS = 5_000
        const val HTTP_TIMEOUT_MS = 3_000
        const val SUBSCRIPTION_STALE_MS = 7L * 24L * 60L * 60L * 1_000L
        const val SERVICE_SAMPLE = 5
    }

    override suspend fun probe(step: DiagnosticStep, context: DoctorContext): StepOutcome =
        withContext(Dispatchers.IO) {
            try {
                when (step) {
                    DiagnosticStep.SUBSCRIPTION -> checkSubscription()
                    DiagnosticStep.PROFILE -> checkProfile()
                    DiagnosticStep.DNS -> checkDns()
                    DiagnosticStep.TCP -> checkTcp()
                    DiagnosticStep.TLS -> checkTls()
                    DiagnosticStep.PROXY -> checkProxy()
                    DiagnosticStep.TUN -> checkTun()
                    DiagnosticStep.TRAFFIC -> checkTraffic()
                    DiagnosticStep.SERVICES -> checkServices()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Class name only: messages can carry endpoints or profile fragments.
                logCollector.warn(TAG, "DOCTOR step=${step.name} failed error=${error.javaClass.simpleName}")
                StepOutcome(
                    DiagnosticStatus.WARN,
                    "Шаг не удалось выполнить. Проверьте вручную.",
                    Remedy.Recheck
                )
            }
        }

    private suspend fun checkSubscription(): StepOutcome {
        val all = subscriptions.subscriptions.value
        if (all.isEmpty()) {
            return StepOutcome(
                DiagnosticStatus.WARN,
                "Подписок нет. Если профили добавлены вручную, это нормально.",
                Remedy.OpenSettings(SettingsSection.SUBSCRIPTIONS)
            )
        }
        val enabled = all.filter { it.enabled }
        if (enabled.isEmpty()) {
            return StepOutcome(
                DiagnosticStatus.FAIL,
                "Все подписки отключены.",
                Remedy.OpenSettings(SettingsSection.SUBSCRIPTIONS)
            )
        }
        val now = System.currentTimeMillis()
        val stale = enabled.count { it.lastUpdated <= 0L || now - it.lastUpdated > SUBSCRIPTION_STALE_MS }

        // Contract of step 1: check the URL outside the tunnel and report only the status code.
        if (VpnProxyService.isRunning.value || VpnProxyService.isCoreTransitioning.value) {
            return StepOutcome(
                DiagnosticStatus.WARN,
                "VPN активен: проверка подписки вне туннеля недоступна.",
                Remedy.Recheck
            )
        }
        val target = enabled.firstOrNull { it.url.isNotBlank() }
        // HEAD + optional GET each get their own socket timeout, so the outer deadline must
        // cover both. runInterruptible inside the probe makes the deadline real.
        val status = target?.let {
            withTimeoutOrNull(HTTP_TIMEOUT_MS * 2 + 1_000L) { probeHttpStatus(it.url) }
        }
        return when {
            target == null -> StepOutcome(
                DiagnosticStatus.WARN,
                "У подписок нет адреса для проверки.",
                Remedy.OpenSettings(SettingsSection.SUBSCRIPTIONS)
            )
            status == null -> StepOutcome(
                DiagnosticStatus.WARN,
                "Подписку не удалось проверить: нет ответа. Возможна блокировка сети.",
                Remedy.RefreshSubscription
            )
            status in 200..399 && stale > 0 -> StepOutcome(
                DiagnosticStatus.WARN,
                "Подписка отвечает: HTTP $status. Давно не обновлялась: $stale из ${enabled.size}.",
                Remedy.RefreshSubscription
            )
            status in 200..399 -> StepOutcome(
                DiagnosticStatus.OK,
                "Подписка отвечает: HTTP $status."
            )
            else -> StepOutcome(
                DiagnosticStatus.FAIL,
                "Подписка вернула HTTP $status. Обновите её или проверьте адрес.",
                Remedy.RefreshSubscription
            )
        }
    }

    private fun checkProfile(): StepOutcome {
        val configs = allConfigs()
        if (configs.isEmpty()) {
            return StepOutcome(
                DiagnosticStatus.FAIL,
                "Профилей нет. Добавьте подписку или конфигурацию.",
                Remedy.ReimportProfile
            )
        }
        val valid = configs.filter { it.isValid() }
        if (valid.isEmpty()) {
            return StepOutcome(
                DiagnosticStatus.FAIL,
                "Нет валидных профилей: проверьте адреса и ключи.",
                Remedy.ReimportProfile
            )
        }
        val active = activeConfig(configs)
        return when {
            active == null -> StepOutcome(
                DiagnosticStatus.WARN,
                "Загружено ${configs.size} профилей, активный не определён. Выберите сервер вручную.",
                Remedy.SwitchServer
            )
            !active.isValid() -> StepOutcome(
                DiagnosticStatus.FAIL,
                "Активный профиль некорректен. Импортируйте его заново.",
                Remedy.ReimportProfile
            )
            else -> StepOutcome(
                DiagnosticStatus.OK,
                "Активный профиль валиден. Загружено ${configs.size} профилей."
            )
        }
    }

    private suspend fun checkDns(): StepOutcome {
        val config = probeConfig() ?: return noProfile()
        // Resolve the server address itself. SNI is a camouflage name and proves nothing
        // about the endpoint the profile actually connects to.
        val host = config.address
        if (isIpLiteral(host)) {
            return StepOutcome(DiagnosticStatus.OK, "Адрес задан IP, DNS не требуется.")
        }
        val resolved = withTimeoutOrNull(DNS_TIMEOUT_MS.toLong()) {
            withContext(Dispatchers.IO) {
                try {
                    // DNS is a blocking call and ignores cancellation without this wrapper.
                    runInterruptible { InetAddress.getAllByName(host).isNotEmpty() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        }
        return if (resolved == true) {
            StepOutcome(DiagnosticStatus.OK, "Имя резолвится.")
        } else {
            StepOutcome(
                DiagnosticStatus.WARN,
                "Не удалось подтвердить DNS. Возможна блокировка DNS.",
                Remedy.OpenSettings(SettingsSection.DNS)
            )
        }
    }

    private suspend fun checkTcp(): StepOutcome {
        val config = probeConfig() ?: return noProfile()
        DoctorProbeRules.tcpSkipReason(config)?.let {
            return StepOutcome(DiagnosticStatus.SKIP, it, skipReason = it)
        }
        val connected = withTimeoutOrNull(TCP_TIMEOUT_MS + 1_000L) {
            withContext(Dispatchers.IO) {
                try {
                    // Blocking connect ignores coroutine cancellation; runInterruptible lets the
                    // step timeout and the total Doctor budget actually stop it.
                    runInterruptible {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(config.address, config.port), TCP_TIMEOUT_MS)
                        }
                    }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        } == true
        return if (connected) {
            StepOutcome(DiagnosticStatus.OK, "TCP-соединение устанавливается.")
        } else {
            StepOutcome(
                DiagnosticStatus.FAIL,
                "TCP-соединение не устанавливается.",
                Remedy.SwitchServer
            )
        }
    }

    private suspend fun checkTls(): StepOutcome {
        val config = probeConfig() ?: return noProfile()
        // The tunnel may have come up after the DoctorContext snapshot. Without this guard the
        // socket would go through the tunnel and a normal handshake result would be misread.
        if (VpnProxyService.isRunning.value || VpnProxyService.isCoreTransitioning.value) {
            return StepOutcome(
                DiagnosticStatus.WARN,
                "VPN ещё не остановлен. TLS-проба не запускалась.",
                Remedy.Recheck
            )
        }
        if (config.security.equals("reality", ignoreCase = true)) {
            // Reality is designed to look like someone else's TLS. A plain handshake proves
            // nothing, so report a warning and leave the verdict to the proxy probe.
            return StepOutcome(
                DiagnosticStatus.WARN,
                "Reality нельзя проверить обычным TLS-рукопожатием. Смотрите прокси-пробу.",
                Remedy.Recheck
            )
        }
        DoctorProbeRules.tlsSkipReason(config)?.let {
            return StepOutcome(DiagnosticStatus.SKIP, it, skipReason = it)
        }
        val host = config.sni?.takeIf { it.isNotBlank() }
            ?: config.host?.takeIf { it.isNotBlank() }
            ?: config.address
        // Connect and handshake are sequential, so the outer budget must cover both.
        val handshake = withTimeoutOrNull(TCP_TIMEOUT_MS + TLS_TIMEOUT_MS + 1_000L) {
            withContext(Dispatchers.IO) {
                try {
                    runInterruptible {
                        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
                            socket.connect(InetSocketAddress(config.address, config.port), TCP_TIMEOUT_MS)
                            // A read timeout makes startHandshake() bounded even if the server
                            // accepts TCP and then stays silent.
                            socket.soTimeout = TLS_TIMEOUT_MS
                            if (!isIpLiteral(host)) {
                                socket.sslParameters = socket.sslParameters.apply {
                                    serverNames = listOf(SNIHostName(host))
                                }
                            }
                            socket.startHandshake()
                        }
                    }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
        } == true
        return if (handshake) {
            StepOutcome(DiagnosticStatus.OK, "TLS-рукопожатие проходит.")
        } else {
            StepOutcome(
                DiagnosticStatus.FAIL,
                "TLS-рукопожатие не проходит. Возможна блокировка по SNI.",
                Remedy.OpenSettings(SettingsSection.DURABILITY)
            )
        }
    }

    private suspend fun checkProxy(): StepOutcome {
        val config = probeConfig() ?: return noProfile()
        if (VpnProxyService.isRunning.value || VpnProxyService.isCoreTransitioning.value) {
            return StepOutcome(
                DiagnosticStatus.WARN,
                "VPN ещё не остановлен. Прокси-проба не запускалась.",
                Remedy.Recheck
            )
        }
        // The engine logs `profile=${config.name}` on a failed sample, and a default profile
        // name is `address:port`. The Doctor already knows the endpoint, so the log must not.
        val probeConfig = config.copy(name = "doctor")
        val result = deepProbeEngine.pingProfiles(listOf(probeConfig)).firstOrNull()
        return if (result?.success == true) {
            StepOutcome(
                DiagnosticStatus.OK,
                "Прокси-проба прошла: ${result.latencyMs.coerceAtLeast(0L)} мс."
            )
        } else {
            StepOutcome(
                DiagnosticStatus.FAIL,
                "Прокси-проба не прошла: профиль не пропускает трафик.",
                Remedy.SwitchServer
            )
        }
    }

    private fun checkTun(): StepOutcome {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = connectivity?.activeNetwork
        val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
        val vpnTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return when {
            VpnProxyService.isRunning.value && vpnTransport ->
                StepOutcome(DiagnosticStatus.OK, "TUN активен, система видит VPN-сеть.")
            VpnProxyService.isRunning.value -> StepOutcome(
                DiagnosticStatus.WARN,
                "VPN запущен, но система не подтверждает VPN-транспорт.",
                Remedy.RestartCore
            )
            else -> StepOutcome(
                DiagnosticStatus.FAIL,
                "VPN не запущен, TUN проверять нечего.",
                Remedy.RestartCore
            )
        }
    }

    private suspend fun checkTraffic(): StepOutcome {
        // Never pass the profile name: it defaults to "address:port" and would leak a server
        // address into the persistent journal that DiagnosticProbe writes.
        val result = DiagnosticProbe.runOnce("doctor", logCollector)
        return if (result.success) {
            StepOutcome(
                DiagnosticStatus.OK,
                "HTTP через туннель проходит за ${result.elapsedMs} мс."
            )
        } else {
            StepOutcome(
                DiagnosticStatus.FAIL,
                "HTTP через туннель не проходит.",
                Remedy.SwitchServer
            )
        }
    }

    private suspend fun checkServices(): StepOutcome {
        val results = serviceChecker.checkAll(
            targets = ServiceCatalog.defaultTargets.take(SERVICE_SAMPLE),
            vpnActive = { VpnProxyService.isRunning.value },
            coreTransitioning = { VpnProxyService.isCoreTransitioning.value }
        ) ?: return StepOutcome(
            DiagnosticStatus.WARN,
            "Проверка сервисов уже идёт.",
            Remedy.Recheck
        )
        val reachable = results.count { it.status == ServiceCheckStatus.REACHABLE }
        val answered = results.count { it.status == ServiceCheckStatus.ANSWERED }
        val unknown = results.size - reachable - answered
        return when {
            reachable == results.size ->
                StepOutcome(DiagnosticStatus.OK, "Все контрольные сервисы доступны.")
            reachable > 0 -> StepOutcome(
                DiagnosticStatus.WARN,
                "Доступны $reachable из ${results.size}; с ошибкой ответили $answered; без вердикта $unknown.",
                Remedy.Recheck
            )
            else -> StepOutcome(
                DiagnosticStatus.WARN,
                "Ни один контрольный сервис не подтвердил доступность; без вердикта $unknown.",
                Remedy.Recheck
            )
        }
    }

    /**
     * Returns the HTTP status only. The URL is never logged, shown or put into the report.
     * HEAD first; some panels reject it with 405, so a GET without reading the body follows.
     *
     * `runInterruptible` is required: a blocking socket call ignores coroutine cancellation,
     * so neither the step timeout nor the total budget could stop it otherwise.
     */
    private suspend fun probeHttpStatus(url: String): Int? = try {
        runInterruptible(Dispatchers.IO) {
            fun request(method: String): Int {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "vladossVPN-doctor")
                }
                return try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }
            val head = request("HEAD")
            if (head == HttpURLConnection.HTTP_BAD_METHOD) request("GET") else head
        }
    } catch (cancelled: CancellationException) {
        // External cancellation (leaving the screen) must propagate, not become a verdict.
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun allConfigs(): List<ParsedConfig> =
        jsonConfigs.allConfigsIncluding(subscriptions.getAllConfigs())

    /**
     * Last successfully selected profile, matched by the one-way fingerprint stored in
     * [LastServerStore]. Falls back to the first valid profile so a fresh install still has
     * something to probe.
     */
    private fun probeConfig(): ParsedConfig? =
        activeConfig(allConfigs().filter { it.isValid() })
            ?: allConfigs().firstOrNull { it.isValid() }

    private fun activeConfig(configs: List<ParsedConfig>): ParsedConfig? {
        val saved = lastServerStore.load() ?: return null
        val fingerprint = saved.fingerprint
        if (fingerprint.isBlank()) return null
        return configs.firstOrNull { lastServerStore.fingerprint(it.connectionKey()) == fingerprint }
    }

    private fun noProfile(): StepOutcome = StepOutcome(
        DiagnosticStatus.WARN,
        "Нет профиля для проверки. Добавьте подписку или конфигурацию.",
        Remedy.ReimportProfile
    )

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
}
