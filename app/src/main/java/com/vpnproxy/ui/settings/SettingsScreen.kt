package com.vpnproxy.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.BuildConfig
import com.vpnproxy.ui.update.UpdateDialog
import com.vpnproxy.ui.update.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val autoStart by viewModel.autoStart.collectAsStateWithLifecycle()
    val killSwitch by viewModel.killSwitch.collectAsStateWithLifecycle()
    val checkUpdates by viewModel.checkUpdates.collectAsStateWithLifecycle()
    val batteryEconomyMode by viewModel.batteryEconomyMode.collectAsStateWithLifecycle()
    val dualPathEnabled by viewModel.dualPathEnabled.collectAsStateWithLifecycle()
    val dualPathSelectedCount by viewModel.dualPathSelectedCount.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val isCheckingUpdates by updateViewModel.isCheckingUpdates.collectAsStateWithLifecycle()

    var showSplitTunnelScreen by remember { mutableStateOf(false) }
    var showDualPathScreen by remember { mutableStateOf(false) }
    var showOpenSourceLicenses by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        updateViewModel.feedback.collect { feedback ->
            when (feedback) {
                is UpdateViewModel.Feedback.UpToDate ->
                    snackbarHostState.showSnackbar("У вас установлена последняя версия")
                is UpdateViewModel.Feedback.Error ->
                    snackbarHostState.showSnackbar(feedback.message)
            }
        }
    }

    if (showSplitTunnelScreen) {
        SplitTunnelScreen(onBack = { showSplitTunnelScreen = false })
        return
    }

    if (showDualPathScreen) {
        DualPathAppsScreen(onBack = { showDualPathScreen = false })
        return
    }

    if (showOpenSourceLicenses) {
        OpenSourceLicensesScreen(onBack = { showOpenSourceLicenses = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Text("БЕЗОПАСНОСТЬ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kill Switch", style = MaterialTheme.typography.bodyLarge)
                    Text("Блокировать трафик при разрыве туннеля", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = killSwitch, onCheckedChange = { viewModel.setKillSwitch(it) })
            }

            Text("СЕТЬ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showSplitTunnelScreen = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Обход VPN", style = MaterialTheme.typography.bodyLarge)
                    Text("Выбрать приложения для туннеля", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                TextButton(onClick = { showSplitTunnelScreen = true }) { Text("Настроить") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showDualPathScreen = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Зарубежный обход", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (!dualPathEnabled) "Выключен"
                        else if (dualPathSelectedCount == 0) "Включён · приложения не выбраны"
                        else "Включён · приложений: $dualPathSelectedCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                TextButton(onClick = { showDualPathScreen = true }) { Text("Выбрать") }
            }

            Text("ПОВЕДЕНИЕ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Автозапуск после перезагрузки", style = MaterialTheme.typography.bodyLarge)
                    Text("Запускать VPN при загрузке устройства", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = autoStart, onCheckedChange = { viewModel.setAutoStart(it) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Экономия батареи", style = MaterialTheme.typography.bodyLarge)
                    Text("Реже проверять серверы и отключить переключения по рейтингу", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = batteryEconomyMode, onCheckedChange = { viewModel.setBatteryEconomyMode(it) })
            }

            Text("ОБНОВЛЕНИЯ ПРИЛОЖЕНИЯ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Проверять обновления", style = MaterialTheme.typography.bodyLarge)
                    Text("Уведомлять о новых версиях", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = checkUpdates, onCheckedChange = { viewModel.setCheckUpdates(it) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Проверить обновления сейчас", style = MaterialTheme.typography.bodyLarge)
                    Text("Запросить GitHub Releases", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                TextButton(
                    onClick = { updateViewModel.check(force = true) },
                    enabled = !isCheckingUpdates
                ) {
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Проверить")
                    }
                }
            }

            Text("О ПРИЛОЖЕНИИ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Версия", modifier = Modifier.weight(1f))
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Flavor", modifier = Modifier.weight(1f))
                Text(if (BuildConfig.APPLICATION_ID.endsWith(".play")) "play" else "direct", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showOpenSourceLicenses = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Открытые лицензии", style = MaterialTheme.typography.bodyLarge)
                    Text("Лицензия VPN-ядра и уведомления", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                TextButton(onClick = { showOpenSourceLicenses = true }) { Text("Открыть") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    UpdateDialog(
        state = updateState,
        onDownloadDownload = { updateViewModel.download() },
        onInstall = { updateViewModel.install() },
        onDismiss = { updateViewModel.dismiss() }
    )
}
