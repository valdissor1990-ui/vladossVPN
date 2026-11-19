package com.vpnproxy.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.smartconnect.ScoringEngine
import com.vpnproxy.subscription.SubscriptionParser
import com.vpnproxy.subscription.SubscriptionRepository
import com.vpnproxy.ui.components.GlowPowerButton
import com.vpnproxy.ui.theme.HappBg
import com.vpnproxy.ui.theme.HappInk
import com.vpnproxy.ui.theme.HappLine
import com.vpnproxy.ui.theme.HappMuted
import com.vpnproxy.ui.theme.HappRemarks
import com.vpnproxy.ui.theme.HappSelected
import com.vpnproxy.ui.update.UpdateDialog
import com.vpnproxy.ui.update.UpdateViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onNavigateToSubscriptions: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") showDebug: Boolean = true,
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val servers by viewModel.profileServers.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var hidden by remember { mutableStateOf(false) }
    var subscriptionMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { updateViewModel.checkOnce() }
    LaunchedEffect(uiState.notice) {
        val text = uiState.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearNotice()
    }

    val selected = subscriptions.firstOrNull { it.id == uiState.selectedSubscriptionId }
        ?: subscriptions.firstOrNull()
    val visibleServers = remember(servers, selected, uiState.selectedSubscriptionId) {
        if (uiState.selectedSubscriptionId == HomeViewModel.ALL_SUBSCRIPTIONS_ID || selected == null) {
            servers
        } else {
            servers.filter { it.config.providerTag == selected.name }
        }
    }
    val quality = linkQuality(
        state = uiState.connectionState,
        rttMs = uiState.currentRtt,
        flapping = uiState.flapping,
        successRate = uiState.currentSuccessRate
    )

    Box(modifier = Modifier.fillMaxSize().background(HappBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = HappInk, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNavigateToDebug) {
                    Icon(Icons.Filled.List, contentDescription = null, tint = HappInk, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Логи", color = HappInk, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить подписку", tint = HappInk, modifier = Modifier.size(28.dp))
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlowPowerButton(
                    state = uiState.connectionState,
                    quality = quality,
                    sessionStartedAt = uiState.sessionStartedAt,
                    isScanning = uiState.isScanningServers,
                    onClick = {
                        when (uiState.connectionState) {
                            ConnectionUiState.CONNECTED,
                            ConnectionUiState.UNSTABLE,
                            ConnectionUiState.CONNECTING,
                            ConnectionUiState.VERIFYING -> viewModel.toggleConnection()
                            ConnectionUiState.DISCONNECTED,
                            ConnectionUiState.ERROR -> {
                                if (uiState.isScanningServers) viewModel.cancelServerScan()
                                else viewModel.toggleConnection()
                            }
                        }
                    }
                )
            }

            if (!uiState.lastErrorMessage.isNullOrBlank() && uiState.connectionState == ConnectionUiState.ERROR) {
                Text(
                    text = uiState.lastErrorMessage.orEmpty(),
                    color = Color(0xFFE23B3B),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 28.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Проверить текущее\nподключение",
                    color = HappMuted,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { viewModel.checkCurrentConnection() }
                        .padding(vertical = 6.dp)
                )
                Text(
                    text = if (hidden) "Показать все" else "Скрыть все",
                    color = HappMuted,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { hidden = !hidden }
                        .padding(vertical = 6.dp)
                )
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = Color.White,
                shadowElevation = 16.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    SubscriptionHeader(
                        subscription = selected,
                        allMode = uiState.selectedSubscriptionId == HomeViewModel.ALL_SUBSCRIPTIONS_ID && subscriptions.size != 1,
                        subscriptions = subscriptions,
                        menuExpanded = subscriptionMenu,
                        onMenuExpanded = { subscriptionMenu = it },
                        onSelectSubscription = { viewModel.setAutomaticSubscription(it) },
                        refreshing = uiState.isRefreshingSubscription,
                        scanning = uiState.isScanningServers,
                        onRefresh = { viewModel.refreshSubscription() },
                        onPing = { viewModel.pingAllServers() },
                        moreExpanded = moreMenu,
                        onMoreExpanded = { moreMenu = it },
                        onOpenLogs = onNavigateToDebug,
                        onOpenSettings = onNavigateToSettings,
                        onOpenSubscriptions = onNavigateToSubscriptions,
                        onDelete = { viewModel.removeSelectedSubscription() },
                        onOpenLink = {
                            val url = selected?.url ?: return@SubscriptionHeader
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    )
                    if (!hidden) {
                        SubscriptionMeta(subscription = selected, onOpenLink = {
                            val url = selected?.url ?: return@SubscriptionMeta
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        })
                        AutoConnectRow(
                            enabled = uiState.isAutomaticMode,
                            busy = uiState.isBusy,
                            onChange = viewModel::setAutomaticMode
                        )
                        HorizontalDivider(color = HappLine)
                        if (visibleServers.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (subscriptions.isEmpty()) "Нет подписок" else "В подписке пока нет серверов",
                                        color = HappMuted
                                    )
                                    TextButton(onClick = { if (subscriptions.isEmpty()) showAdd = true else viewModel.refreshSubscription() }) {
                                        Text(if (subscriptions.isEmpty()) "Добавить" else "Обновить")
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 18.dp)
                            ) {
                                items(visibleServers, key = { it.config.connectionKey() }) { server ->
                                    val active = server.config.connectionKey() == uiState.selectedServerKey ||
                                        (uiState.selectedServerKey.isBlank() &&
                                            uiState.connectionState.hasTunnel() &&
                                            server.config.name == uiState.currentServerName)
                                    ServerRow(
                                        server = server,
                                        selected = active,
                                        onClick = { viewModel.onServerSelected(server) }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                            Text("Список серверов скрыт", color = HappMuted, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
        )
    }

    if (showAdd) {
        AddSubscriptionDialog(
            onDismiss = { showAdd = false },
            onConfirm = { url, name ->
                viewModel.addSubscription(url, name)
                showAdd = false
            }
        )
    }

    UpdateDialog(
        state = updateState,
        onDownload = { updateViewModel.download() },
        onInstall = { updateViewModel.install() },
        onDismiss = { updateViewModel.dismiss() }
    )
}

@Composable
private fun SubscriptionHeader(
    subscription: SubscriptionRepository.ProviderSubscription?,
    allMode: Boolean,
    subscriptions: List<SubscriptionRepository.ProviderSubscription>,
    menuExpanded: Boolean,
    onMenuExpanded: (Boolean) -> Unit,
    onSelectSubscription: (String) -> Unit,
    refreshing: Boolean,
    scanning: Boolean,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    moreExpanded: Boolean,
    onMoreExpanded: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onDelete: () -> Unit,
    onOpenLink: () -> Unit
) {
    val title = when {
        subscription == null -> "Нет подписки"
        allMode -> "Все подписки"
        else -> hostOf(subscription.url).ifBlank { subscription.name }
    }
    val updated = subscription?.lastUpdated?.takeIf { it > 0L }?.let { formatDateTime(it) } ?: "не обновлялась"
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { onMenuExpanded(true) }) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "Список подписок", tint = HappInk)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { onMenuExpanded(false) }) {
                    DropdownMenuItem(
                        text = { Text("Все подписки") },
                        onClick = {
                            onSelectSubscription(HomeViewModel.ALL_SUBSCRIPTIONS_ID)
                            onMenuExpanded(false)
                        }
                    )
                    subscriptions.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.name.ifBlank { hostOf(item.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                onSelectSubscription(item.id)
                                onMenuExpanded(false)
                            }
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).clickable { onMenuExpanded(true) }) {
                Text(title, color = HappInk, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$updated | Автообновление", color = HappMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = HappSelected)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить подписку", tint = HappMuted)
                }
            }
            IconButton(onClick = onPing, enabled = !scanning) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = HappSelected)
                } else {
                    Icon(Icons.Filled.Speed, contentDescription = "Проверить задержку", tint = HappMuted)
                }
            }
            Box {
                IconButton(onClick = { onMoreExpanded(true) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Ещё", tint = HappMuted)
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { onMoreExpanded(false) }) {
                    DropdownMenuItem(text = { Text("Журнал логов") }, onClick = { onMoreExpanded(false); onOpenLogs() })
                    DropdownMenuItem(text = { Text("Настройки") }, onClick = { onMoreExpanded(false); onOpenSettings() })
                    DropdownMenuItem(text = { Text("Все подписки") }, onClick = { onMoreExpanded(false); onOpenSubscriptions() })
                    DropdownMenuItem(text = { Text("Открыть ссылку") }, onClick = { onMoreExpanded(false); onOpenLink() })
                    DropdownMenuItem(text = { Text("Удалить подписку") }, onClick = { onMoreExpanded(false); onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SubscriptionMeta(
    subscription: SubscriptionRepository.ProviderSubscription?,
    onOpenLink: () -> Unit
) {
    val used = subscription?.trafficUsed ?: 0L
    val total = subscription?.trafficTotal ?: 0L
    val expires = subscription?.expiresAt ?: 0L
    val days = if (expires > System.currentTimeMillis()) {
        TimeUnit.MILLISECONDS.toDays(expires - System.currentTimeMillis())
    } else null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(HappRemarks)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = HappSelected, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTraffic(used, total),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = HappInk,
                fontSize = 13.sp
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (expires > 0L) "Истекает: ${formatDate(expires)}" else "Истекает: —",
                color = HappInk,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onOpenLink, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Send, contentDescription = "Открыть подписку", tint = HappSelected)
            }
        }
        Spacer(Modifier.height(8.dp))
        val note = subscription?.tag?.takeIf { it.isNotBlank() && it != subscription.name }
        Text(
            text = buildString {
                if (!note.isNullOrBlank()) append(note).append('\n')
                if (days != null) append("Осталось $days дн.") else append("Срок подписки не указан")
            },
            color = HappInk.copy(alpha = 0.8f),
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun AutoConnectRow(
    enabled: Boolean,
    busy: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Автоподключение", color = HappInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                if (enabled) "Само выбирает самый стабильный сервер" else "Подключение только к выбранному серверу",
                color = HappMuted,
                fontSize = 12.sp
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange, enabled = !busy)
    }
}

@Composable
private fun ServerRow(
    server: ScoringEngine.ScoredServer,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFFF7F8FF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 0.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(42.dp)
                .background(if (selected) HappSelected else Color.Transparent)
        )
        Spacer(Modifier.width(12.dp))
        Text(flagFor(server.config), fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                server.config.name.ifBlank { server.config.address },
                color = HappInk,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(protocolLine(server.config), color = HappMuted, fontSize = 12.sp, maxLines = 1)
        }
        Text(formatLatency(server.latencyMs), color = HappMuted, fontSize = 14.sp)
    }
    HorizontalDivider(color = HappLine, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить подписку") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL подписки") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim(), name.trim()) }, enabled = url.isNotBlank()) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").substringBefore("/").substringBefore("?")

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(epochMs))

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun formatTraffic(used: Long, total: Long): String {
    fun gb(bytes: Long): String {
        val value = bytes / 1_000_000_000.0
        return String.format(Locale("ru"), "%.1f", value).replace('.', ',')
    }
    val totalLabel = if (total <= 0L) "∞" else "${gb(total)}GB"
    return "${gb(used)}GB/$totalLabel"
}

private fun formatLatency(ms: Long): String =
    if (ms <= 0L || ms == Long.MAX_VALUE) "—" else "${ms}мс"

private fun protocolLine(config: SubscriptionParser.ParsedConfig): String {
    val head = when (config.transport.lowercase(Locale.ROOT)) {
        "hysteria2", "hy2" -> "HY2"
        "tuic" -> "TUIC"
        "shadowsocks", "ss" -> "SS"
        "trojan" -> "TROJAN"
        "vmess" -> "VMESS"
        "socks" -> "SOCKS"
        else -> "VLESS"
    }
    val transport = config.transport.uppercase(Locale.ROOT).ifBlank { "TCP" }
    val security = config.security.uppercase(Locale.ROOT).ifBlank { "NONE" }
    return "$head / $transport / $security"
}

private fun flagFor(config: SubscriptionParser.ParsedConfig): String {
    val blob = "${config.name} ${config.address} ${config.sni.orEmpty()}".lowercase(Locale.ROOT)
    return when {
        listOf("росси", "moscow", "моск", "игров", "банк", ".ru", "spb", "петер").any { blob.contains(it) } -> "🇷🇺"
        listOf("нидер", "amsterdam", "амстер", "антиглуш", "netherlands").any { blob.contains(it) } -> "🇳🇱"
        listOf("герман", "frankfurt", "berlin", "germany").any { blob.contains(it) } -> "🇩🇪"
        listOf("финлянд", "helsinki", "finland").any { blob.contains(it) } -> "🇫🇮"
        listOf("сша", "united states", "new york", "los angeles", "ashburn").any { blob.contains(it) } -> "🇺🇸"
        listOf("евро", "europe", "авто", "wi-fi", "wifi").any { blob.contains(it) } -> "🇪🇺"
        else -> "🌐"
    }
}
