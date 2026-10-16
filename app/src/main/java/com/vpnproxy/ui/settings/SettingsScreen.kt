package com.vpnproxy.ui.settings

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
import com.vpnproxy.update.UpdateManager
import kotlinx.coroutines.delay

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
    val updateInterval by viewModel.updateInterval.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    var showIntervalDialog by remember { mutableStateOf(false) }
    val intervals = listOf(1, 3, 6, 12, 24)

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
        }
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

            Text("ПОВЕДЕНИЕ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Автозапуск после перезагрузки", style = MaterialTheme.typography.bodyLarge)
                    Text("Запускать VPN при загрузке устройства", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = autoStart, onCheckedChange = { viewModel.setAutoStart(it) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Интервал обновления подписок", style = MaterialTheme.typography.bodyLarge)
                    Text("Каждые $updateInterval ч", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                TextButton(onClick = { showIntervalDialog = true }) { Text("Изменить") }
            }

            Text("ОБНОВЛЕНИЯ ПРИЛОЖЕНИЯ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Проверять обновления", style = MaterialTheme.typography.bodyLarge)
                    Text("Уведомлять о новых версиях", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                Switch(checked = checkUpdates, onCheckedChange = { viewModel.setCheckUpdates(it) })
            }

            // Кнопка проверки + inline-индикатор статуса
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Проверить обновления сейчас", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = when (val s = updateState) {
                            is UpdateManager.State.Checking -> "Загрузка с GitHub…"
                            is UpdateManager.State.UpToDate -> "Обновлений нет"
                            is UpdateManager.State.UpdateAvailable -> "Доступна версия ${s.release.extractVersionName()}"
                            is UpdateManager.State.Downloading -> "Скачивание: ${s.percent}%"
                            is UpdateManager.State.Ready -> "Готово к установке"
                            is UpdateManager.State.Error -> "Не удалось: ${s.message.take(60)}"
                            else -> "Нажмите для проверки"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (updateState) {
                            is UpdateManager.State.Error -> MaterialTheme.colorScheme.error
                            is UpdateManager.State.UpToDate -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (updateState is UpdateManager.State.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Button(onClick = { updateViewModel.check(force = true) }) {
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

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showIntervalDialog = false },
            title = { Text("Интервал обновления") },
            text = {
                Column {
                    intervals.forEach { h ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = updateInterval == h, onClick = { viewModel.setUpdateInterval(h); showIntervalDialog = false })
                            Spacer(Modifier.width(8.dp))
                            Text("Каждые $h ч")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showIntervalDialog = false }) { Text("Отмена") } }
        )
    }

    // Авто-скрытие "Обновлений нет" / "Ошибки" через 3 секунды
    LaunchedEffect(updateState) {
        if (updateState is UpdateManager.State.UpToDate ||
            updateState is UpdateManager.State.Error) {
            delay(3000)
            updateViewModel.dismiss()
        }
    }

    UpdateDialog(
        state = updateState,
        onDownload = { updateViewModel.download() },
        onInstall = { updateViewModel.install() },
        onDismiss = { updateViewModel.dismiss() }
    )
}
