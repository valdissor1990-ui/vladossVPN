package com.vpnproxy.ui.servers

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.ping.LatencyBreakdown
import com.vpnproxy.core.ping.LatencyCalculator
import com.vpnproxy.core.ping.PingService
import com.vpnproxy.core.ping.PingTargets
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ServerMetrics
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ServersListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SubscriptionRepository,
    private val autoSwitcher: AutoSwitcher,
    private val pingService: PingService
) : ViewModel() {

    private val _servers = MutableStateFlow<List<ScoredServer>>(emptyList())
    val servers: StateFlow<List<ScoredServer>> = _servers.asStateFlow()

    private val _breakdown = MutableStateFlow<LatencyBreakdown?>(null)
    val breakdown: StateFlow<LatencyBreakdown?> = _breakdown.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allConfigs.collect { configs ->
                if (configs.isNotEmpty() && autoSwitcher.rankedServers.value.isEmpty()) {
                    autoSwitcher.selectBest(configs)
                }
                _servers.value = autoSwitcher.rankedServers.value
            }
        }
        viewModelScope.launch {
            autoSwitcher.rankedServers.collect { _servers.value = it }
        }
        viewModelScope.launch { repository.refreshAll() }
        viewModelScope.launch {
            runCatching {
                val region = PingTargets.detectRegion(context)
                val multi = pingService.pingAll(
                    targets = PingTargets.forRegion(region),
                    region = region
                )
                _breakdown.value = LatencyCalculator.compute(multi.results)
            }
        }
    }
}

/**
 * 2-колоночный grid + expand per-target breakdown (Фичи 3+4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListContent(
    onServerSelected: (ScoredServer) -> Unit = {},
    viewModel: ServersListViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var expandedKey by remember { mutableStateOf<String?>(null) }

    val filtered = remember(servers, query) {
        if (query.isBlank()) servers
        else servers.filter {
            it.config.name.contains(query, ignoreCase = true) ||
                it.config.address.contains(query, ignoreCase = true) ||
                it.config.providerTag.contains(query, ignoreCase = true)
        }
    }

    val uniqueItems = remember(filtered) {
        filtered.distinctBy {
            "${it.config.address}|${it.config.port}|${it.config.uuid}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Серверы (${servers.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Поиск по имени/хосту/провайдеру") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (servers.isEmpty()) {
                        "Список пуст. Обновите подписки."
                    } else {
                        "Ничего не найдено"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 520.dp)
            ) {
                items(
                    items = uniqueItems,
                    key = {
                        ServerMetrics.serverKey(it.config.address, it.config.port, it.config.uuid)
                    }
                ) { scoredServer ->
                    val key = ServerMetrics.serverKey(
                        scoredServer.config.address,
                        scoredServer.config.port,
                        scoredServer.config.uuid
                    )
                    val expanded = expandedKey == key
                    val scoreMs = breakdown?.compositeScore?.toLong()
                        ?: scoredServer.latencyMs.takeIf { it > 0 }
                    val scoreLabel = when {
                        breakdown != null && !breakdown!!.isValid -> "Slow"
                        scoreMs != null && scoreMs > 0 -> "${scoreMs}ms"
                        else -> "—"
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedKey = if (expanded) null else key
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column {
                            ServerGridCard(
                                server = scoredServer,
                                scoreOverride = scoreLabel,
                                onClick = {
                                    expandedKey = if (expanded) null else key
                                }
                            )
                            if (expanded) {
                                Column(Modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    val targets = breakdown?.perTarget.orEmpty()
                                    if (targets.isEmpty()) {
                                        Text(
                                            text = "Ping targets: waiting…",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    } else {
                                        targets.forEach { t ->
                                            val label = when {
                                                t.success && t.rttMs != null -> "${t.targetId}: ${t.rttMs}ms"
                                                else -> "${t.targetId}: fail"
                                            }
                                            val c = if (t.success && t.rttMs != null) {
                                                rttColor(t.rttMs)
                                            } else {
                                                Color(0xFFE53935)
                                            }
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = c,
                                                modifier = Modifier.padding(vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { onServerSelected(scoredServer) }) {
                                            Text("Подключить", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rttColor(rtt: Long): Color = when {
    rtt <= 0L -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    rtt < 100 -> Color(0xFF43A047)
    rtt < 300 -> Color(0xFFF9A825)
    else -> Color(0xFFE53935)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersListScreen(
    onBack: () -> Unit,
    viewModel: ServersListViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ServerListContent(viewModel = viewModel)
        }
    }
}
