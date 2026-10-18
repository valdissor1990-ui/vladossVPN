package com.vpnproxy.ui.servers

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.vpnproxy.core.ping.PingRegion
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
import kotlin.math.roundToInt

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListContent(
    onServerSelected: (ScoredServer) -> Unit = {},
    viewModel: ServersListViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var detailsServer by remember { mutableStateOf<ScoredServer?>(null) }
    val targetNames = remember {
        (PingTargets.ALL + PingTargets.forRegion(PingRegion.EU))
            .associate { it.id to it.displayName }
    }

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
                    val scoreLabel = breakdown
                        ?.takeIf { it.isValid }
                        ?.compositeScore
                        ?.roundToInt()
                        ?.toString() ?: "—"
                    val scoreValue = scoreLabel.toDoubleOrNull()
                    val scoreColor = scoreValue?.let { rttColor(it.toLong()) }
                        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

                    ServerGridCard(
                        server = scoredServer,
                        onClick = { onServerSelected(scoredServer) },
                        scoreOverride = scoreLabel,
                        scoreColorOverride = scoreColor,
                        onDetailsClick = { detailsServer = scoredServer }
                    )
                }
            }
        }
    }

    detailsServer?.let { server ->
        ModalBottomSheet(onDismissRequest = { detailsServer = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Детали пинга",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = server.config.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                val score = breakdown?.takeIf { it.isValid }?.compositeScore?.roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Общая оценка", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    Text(
                        text = score?.let { "${it} ms" } ?: "Недостаточно данных",
                        color = score?.let { rttColor(it.toLong()) }
                            ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                val targets = breakdown?.perTarget.orEmpty()
                if (targets.isEmpty()) {
                    Text(
                        "Данные пинга пока недоступны.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    targets.forEach { target ->
                        val name = targetNames[target.targetId] ?: target.targetId
                        val label = if (target.success && target.rttMs != null) {
                            "$name: ${target.rttMs} ms"
                        } else {
                            "$name: недоступен"
                        }
                        val color = if (target.success && target.rttMs != null) {
                            rttColor(target.rttMs)
                        } else {
                            Color(0xFFE53935)
                        }
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { detailsServer = null }) {
                        Text("Закрыть")
                    }
                    TextButton(onClick = {
                        detailsServer = null
                        onServerSelected(server)
                    }) {
                        Text("Подключить")
                    }
                }
            }
        }
    }
}

private fun rttColor(rtt: Long): Color = when {
    rtt < 100L -> Color(0xFF43A047)
    rtt < 300L -> Color(0xFFF9A825)
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
