package com.vpnproxy.ui.servers

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.service.VpnProxyService
import com.vpnproxy.smartconnect.DeepProbeEngine
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionParser.ParsedConfig
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersListViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val deepProbeEngine: DeepProbeEngine,
    private val logCollector: LogCollector,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _servers = MutableStateFlow<List<ParsedConfig>>(emptyList())
    val servers: StateFlow<List<ParsedConfig>> = _servers.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _deepProbeRunning = MutableStateFlow(false)
    val deepProbeRunning: StateFlow<Boolean> = _deepProbeRunning.asStateFlow()
    private val _deepProbeResults = MutableStateFlow<List<DeepProbeEngine.ProbeMetrics>>(emptyList())
    val deepProbeResults: StateFlow<List<DeepProbeEngine.ProbeMetrics>> = _deepProbeResults.asStateFlow()
    private val _deepProbeError = MutableStateFlow<String?>(null)
    val deepProbeError: StateFlow<String?> = _deepProbeError.asStateFlow()
    private var deepProbeJob: kotlinx.coroutines.Job? = null

    fun startDeepProbe(configs: List<ParsedConfig>) {
        if (_deepProbeRunning.value) return
        deepProbeJob?.cancel()
        deepProbeJob = viewModelScope.launch {
            _deepProbeRunning.value = true
            _deepProbeError.value = null
            try {
                val results = deepProbeEngine.run(configs)
                _deepProbeResults.value = results
            } catch (e: kotlinx.coroutines.CancellationException) {
                deepProbeEngine.cancel()
                throw e
            } catch (e: Exception) {
                _deepProbeError.value = e.message ?: e.toString()
            } finally {
                _deepProbeRunning.value = false
            }
        }
    }

    override fun onCleared() {
        deepProbeJob?.cancel()
        deepProbeEngine.cancel()
        super.onCleared()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                subscriptionRepository.refreshAll()
                _servers.value = subscriptionRepository.getAllConfigs()
            } catch (e: Exception) {
                logCollector.error("ServersList", "Refresh failed", e)
            } finally {
                _loading.value = false
            }
        }
    }

    init {
        refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersListScreen(
    viewModel: ServersListViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val deepProbeRunning by viewModel.deepProbeRunning.collectAsStateWithLifecycle()
    val deepProbeResults by viewModel.deepProbeResults.collectAsStateWithLifecycle()
    val deepProbeError by viewModel.deepProbeError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Button(
                onClick = { viewModel.startDeepProbe(servers) },
                enabled = !deepProbeRunning && servers.isNotEmpty()
            ) {
                Text(if (deepProbeRunning) "Анализ…" else "Глубокий анализ")
            }
            if (deepProbeRunning) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }
            deepProbeError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (deepProbeResults.isNotEmpty()) {
                Text(
                    "Результаты deep probe:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(deepProbeResults, key = { it.config.uuid + it.config.address }) { metric ->
                        Text(
                            "${metric.config.name}: ${metric.medianLatencyMs}ms, ${String.format("%.1f", metric.speedMbps)} Mbps, score=${String.format("%.2f", metric.normalizedScore)}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(servers, key = { it.uuid + it.address + it.port }) { server ->
                        Text(
                            server.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ServerListContent(viewModel: ServersListViewModel) {
    // placeholder for composition reuse if needed
}
