package com.vpnproxy.ui.servers

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
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
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.core.ping.PingTargets
import com.vpnproxy.smartconnect.DeepProbeEngine
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
    private val serverScanner: ServerScanner,
    private val pingService: PingService,
    private val deepProbeEngine: DeepProbeEngine
) : ViewModel() {

    val isScanningServers: StateFlow<Boolean> = serverScanner.isScanning
    val scanResults: StateFlow<List<ScoredServer>> = serverScanner.scannedServers
    fun isRussianServer(config: com.vpnproxy.subscription.SubscriptionParser.ParsedConfig): Boolean =
        serverScanner.isRussianServer(config)
    fun configKey(config: com.vpnproxy.subscription.SubscriptionParser.ParsedConfig): String =
        serverScanner.configKey(config)

    private val _servers = MutableStateFlow<List<ScoredServer>>(emptyList())
    val servers: StateFlow<List<ScoredServer>> = _servers.asStateFlow()

    private val _breakdown = MutableStateFlow<LatencyBreakdown?>(null)
    val breakdown: StateFlow<LatencyBreakdown?> = _breakdown.asStateFlow()

    private val _deepProbeRunning = MutableStateFlow(false)
    val deepProbeRunning: StateFlow<Boolean> = _deepProbeRunning.asStateFlow()
    private val _deepProbeResults = MutableStateFlow<List<DeepProbeEngine.ProbeMetrics>>(emptyList())
    val deepProbeResults: StateFlow<List<DeepProbeEngine.ProbeMetrics>> = _deepProbeResults.asStateFlow()
    private val _deepProbeError = MutableStateFlow<String?>(null)
    val deepProbeError: StateFlow<String?> = _deepProbeError.asStateFlow()
    private var deepProbeJob: kotlinx.coroutines.Job? = null
    private var currentConfigs: List<com.vpnproxy.subscription.SubscriptionParser.ParsedConfig> = emptyList()
    private var currentScanResults: List<ScoredServer> = emptyList()

    private fun updateServerRows() {
        val scanned = currentScanResults.associateBy { serverScanner.configKey(it.config) }
        _servers.value = currentConfigs
            .distinctBy { serverScanner.configKey(it) }
            .map { config ->
                val key = serverScanner.configKey(config)
                scanned[key] ?: ScoredServer(config, Long.MAX_VALUE, 0f, -1f, 0f)
            }.sortedWith(compareBy<ScoredServer>({
            when { it.successRate > 0.5f -> 0; it.successRate == 0f -> 1; else -> 2 }
        }, { it.latencyMs }))
    }

    fun rescanServers() {
        if (serverScanner.isScanning.value) return
        viewModelScope.launch {
            val configs = repository.getAllConfigs()
            serverScanner.scanAll(configs, force = true)
        }
    }

    fun runDeepProbe() {
        if (_deepProbeRunning.value) return
        deepProbeJob?.cancel()
        deepProbeJob = viewModelScope.launch {
            _deepProbeRunning.value = true
            _deepProbeError.value = null
            try {
                val configs = repository.getAllConfigs()
                val results = deepProbeEngine.run(configs)
                _deepProbeResults.value = results
                Toast.makeText(context, "Глубокий анализ завершен", Toast.LENGTH_SHORT).show()
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

    init {
        viewModelScope.launch {
            repository.allConfigs.collect { configs ->
                currentConfigs = configs
                updateServerRows()
            }
        }
        viewModelScope.launch {
            serverScanner.scannedServers.collect { results ->
                currentScanResults = results
                updateServerRows()
            }
        }
        viewModelScope.launch {
            val count = repository.refreshAll()
            serverScanner.scanAfterSubscriptionUpdate(repository.getAllConfigs())
        }
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
    onNavigateHome: () -> Unit = {},
    onNavigateSettings: () -> Unit = {},
    viewModel: ServersListViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
    val deepProbeRunning by viewModel.deepProbeRunning.collectAsStateWithLifecycle()
    val scanningServers by viewModel.isScanningServers.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val deepProbeResults by viewModel.deepProbeResults.collectAsStateWithLifecycle()
    val deepProbeError by viewModel.deepProbeError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Все") }
    var detailsServer by remember { mutableStateOf<ScoredServer?>(null) }
    val targetNames = remember {
        (PingTargets.ALL + PingTargets.forRegion(PingRegion.EU))
            .associate { it.id to it.displayName }
    }

    val filterOptions = remember(servers) {
        listOf("Все", "VLESS", "HY2", "TUIC") +
            servers.map { it.config.providerTag.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .filterNot { it in setOf("Все", "VLESS", "HY2", "TUIC") }
    }

    val filtered = remember(servers, query, selectedFilter) {
        servers.filter { server ->
            val config = server.config
            val transport = config.transport.lowercase()
            val matchesFilter = when (selectedFilter) {
                "Все" -> true
                "HY2" -> transport in setOf("hysteria2", "hy2")
                "TUIC" -> transport == "tuic"
                "VLESS" -> transport !in setOf("hysteria2", "hy2", "tuic")
                else -> config.providerTag.equals(selectedFilter, ignoreCase = true)
            }
            val matchesQuery = query.isBlank() ||
                config.name.contains(query, ignoreCase = true) ||
                config.address.contains(query, ignoreCase = true) ||
                config.providerTag.contains(query, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    val uniqueItems = remember(filtered) {
        filtered.distinctBy { viewModel.configKey(it.config) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF10183E), Color(0xFF20245B),
                        Color(0xFF0E647B), Color(0xFF111638)
                    )
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Серверы (${servers.size})",
                    fontSize = 30.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    color = Color(0xFFD7C4FF)
                )
                Text(
                    if (scanningServers) "Проверка доступности…" else "Выберите сервер для подключения",
                    fontSize = 13.sp,
                    color = Color(0xFFD5E6FF).copy(alpha = 0.8f)
                )
            }
            if (scanningServers || deepProbeRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    color = Color(0xFF9DEBFF),
                    strokeWidth = 2.dp
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Поиск сервера…", color = Color(0xFFDDE7FF).copy(alpha = 0.7f)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск", tint = Color(0xFFE4EEFF)) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFB9CFFF).copy(alpha = 0.85f),
                unfocusedBorderColor = Color(0xFFB9CFFF).copy(alpha = 0.35f),
                cursorColor = Color.White,
                focusedContainerColor = Color(0xFFB7D9FF).copy(alpha = 0.13f),
                unfocusedContainerColor = Color(0xFFB7D9FF).copy(alpha = 0.10f)
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filterOptions.forEach { filter ->
                val selected = selectedFilter == filter
                FilterChip(
                    selected = selected,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.uppercase()) },
                    shape = RoundedCornerShape(22.dp),
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF9DABF5).copy(alpha = 0.85f),
                        selectedLabelColor = Color(0xFF18204D),
                        containerColor = Color(0xFF9CB9EE).copy(alpha = 0.13f),
                        labelColor = Color(0xFFE5EDFF)
                    ),
                    border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = Color.White.copy(alpha = 0.18f),
                        selectedBorderColor = Color(0xFFD7C7FF)
                    )
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.rescanServers() }, enabled = !scanningServers) {
                Text("↻ Сканировать", color = Color(0xFFD7F7FF))
            }
            TextButton(onClick = { viewModel.runDeepProbe() }, enabled = !deepProbeRunning) {
                Text(if (deepProbeRunning) "Анализ…" else "Глубокий анализ", color = Color(0xFFD7F7FF))
            }
        }

        deepProbeError?.let { error ->
            Text(
                text = error,
                color = Color(0xFFFFB6C1),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (deepProbeResults.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Результаты глубокого анализа", color = Color.White, style = MaterialTheme.typography.titleSmall)
                deepProbeResults.forEach { result ->
                    Text(
                        text = "${result.config.name}: score ${"%.3f".format(java.util.Locale.US, result.normalizedScore)} " +
                            "(lat ${"%.2f".format(java.util.Locale.US, result.latencyScore)}, speed ${"%.2f".format(java.util.Locale.US, result.speedScore)}, " +
                            "jitter ${"%.2f".format(java.util.Locale.US, result.jitterScore)}, loss ${"%.2f".format(java.util.Locale.US, result.lossScore)})",
                        fontSize = 11.sp,
                        color = Color(0xFFD5E6FF),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (servers.isEmpty()) "Список пуст. Обновите подписки." else "Ничего не найдено",
                    color = Color(0xFFDDE7FF).copy(alpha = 0.8f)
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(
                    items = uniqueItems,
                    key = { viewModel.configKey(it.config) }
                ) { scoredServer ->
                    val scoreLabel = breakdown?.takeIf { it.isValid }?.compositeScore?.roundToInt()?.toString() ?: "—"
                    val scoreValue = scoreLabel.toDoubleOrNull()
                    val scoreColor = scoreValue?.let { compositeScoreColor(it) } ?: Color(0xFFDDE7FF).copy(alpha = 0.65f)
                    GlassServerCard(
                        server = scoredServer,
                        scoreLabel = scoreLabel,
                        scoreColor = scoreColor,
                        isOnline = scoredServer.successRate > 0.5f,
                        onClick = { onServerSelected(scoredServer) },
                        onDetailsClick = { detailsServer = scoredServer }
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101A49).copy(alpha = 0.86f))
                .border(1.dp, Color.White.copy(alpha = 0.18f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { viewModel.rescanServers() }) {
                Text("▤  Серверы", color = Color.White)
            }
            TextButton(onClick = onNavigateHome) {
                Text("⏻  Подключение", color = Color(0xFFD5E6FF))
            }
            TextButton(onClick = onNavigateSettings) {
                Text("⚙  Настройки", color = Color(0xFFD5E6FF))
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
                        color = score?.let { compositeScoreColor(it.toDouble()) }
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
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { detailsServer = null }) {
                        Text("Закрыть")
                    }
                    TextButton(onClick = {
                        onServerSelected(server)
                        detailsServer = null
                    }) {
                        Text("Выбрать")
                    }
                }
            }
        }
    }
}

private fun extractFlagForGlassCard(name: String): String {
    val match = Regex("[\\uD83C][\\uDDE6-\\uDDFF]{2}").find(name)
    return match?.value.orEmpty()
}

@Composable
private fun GlassServerCard(
    server: ScoredServer,
    scoreLabel: String,
    scoreColor: Color,
    isOnline: Boolean,
    onClick: () -> Unit,
    onDetailsClick: () -> Unit
) {
    val config = server.config
    val flag = extractFlagForGlassCard(config.name)
    val title = config.name.replace(flag, "").trim().ifBlank { config.name }
    val transport = when (config.transport.lowercase()) {
        "hysteria2", "hy2" -> "HY2"
        "tuic" -> "TUIC"
        else -> config.transport.uppercase().ifBlank { "VLESS" }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFB7D7FF).copy(alpha = 0.24f), Color(0xFF8EDCFF).copy(alpha = 0.13f))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (flag.isNotBlank()) {
            Text(flag, fontSize = 30.sp)
            Spacer(Modifier.width(12.dp))
        } else {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFB9D5FF).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Text("VPN", fontSize = 9.sp, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "${config.address}:${config.port}",
                color = Color(0xFFE2EEFF),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (isOnline) Color(0xFF39E98A) else Color(0xFFFF7777)))
                Spacer(Modifier.width(5.dp))
                Text(if (isOnline) "Доступен" else "Не проверен / офлайн", fontSize = 10.sp, color = Color(0xFFDDE7FF).copy(alpha = 0.85f))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.13f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(transport, color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                if (scoreLabel == "—") "— ms" else "${scoreLabel} ms",
                color = scoreColor,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            TextButton(
                onClick = onDetailsClick,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Text("Детали", fontSize = 11.sp, color = Color(0xFFE6F3FF))
            }
        }
    }
}

private fun compositeScoreColor(score: Double): Color = when {
    score < 100.0 -> Color(0xFF43A047)
    score <= 300.0 -> Color(0xFFF9A825)
    else -> Color(0xFFE53935)
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
    onNavigateHome: () -> Unit = onBack,
    onNavigateSettings: () -> Unit = {},
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
            ServerListContent(viewModel = viewModel, onNavigateHome = onNavigateHome, onNavigateSettings = onNavigateSettings)
        }
    }
}
