package com.vpnproxy.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.BuildConfig
import com.vpnproxy.core.singbox.RoutingPreset
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
    val checkUpdates by viewModel.checkUpdates.collectAsStateWithLifecycle()
    val batteryEconomyMode by viewModel.batteryEconomyMode.collectAsStateWithLifecycle()
    val dualPathEnabled by viewModel.dualPathEnabled.collectAsStateWithLifecycle()
    val dualPathSelectedCount by viewModel.dualPathSelectedCount.collectAsStateWithLifecycle()
    val deviceMetadataConsent by viewModel.deviceMetadataConsent.collectAsStateWithLifecycle()
    val routingPreset by viewModel.routingPreset.collectAsStateWithLifecycle()
    val ruleSetState by viewModel.ruleSetState.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val isCheckingUpdates by updateViewModel.isCheckingUpdates.collectAsStateWithLifecycle()

    var showSplitTunnelScreen by remember { mutableStateOf(false) }
    var showDualPathScreen by remember { mutableStateOf(false) }
    var showOpenSourceLicenses by remember { mutableStateOf(false) }
    var showDeviceMetadataConsentDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Системная блокировка без VPN", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Приложение не может самостоятельно включить системный Kill Switch. В настройках Android откройте VPN для vladossVPN, включите «Всегда включённый VPN» и «Блокировать соединения без VPN».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "Откройте Настройки Android → Сеть и интернет → VPN",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) {
                    Text("Открыть настройки VPN")
                }
            }

            Text("КОНФИДЕНЦИАЛЬНОСТЬ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Передавать данные устройства подписке", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (deviceMetadataConsent) "Разрешено: HWID, модель и версия Android"
                        else "Выключено: эти данные не отправляются",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = deviceMetadataConsent,
                    onCheckedChange = { enabled ->
                        if (enabled) showDeviceMetadataConsentDialog = true
                        else viewModel.setDeviceMetadataConsent(false)
                    }
                )
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

            Text("МАРШРУТИЗАЦИЯ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
            RoutingPreset.entries.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setRoutingPreset(preset) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = routingPreset == preset,
                        onClick = { viewModel.setRoutingPreset(preset) }
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(routingPresetTitle(preset), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            routingPresetDescription(preset),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            if (routingPreset == RoutingPreset.BYPASS_RU) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (statusText, canRetry) = when (val state = ruleSetState) {
                        is SettingsViewModel.RuleSetState.Downloading -> "Загрузка правил для РФ…" to false
                        is SettingsViewModel.RuleSetState.Ready -> "Правила для РФ загружены" to true
                        is SettingsViewModel.RuleSetState.Failed -> "Ошибка: ${state.message}" to true
                        SettingsViewModel.RuleSetState.Idle -> "Правила для РФ ещё не загружены" to true
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    if (ruleSetState is SettingsViewModel.RuleSetState.Downloading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (canRetry) {
                        TextButton(onClick = { viewModel.refreshRuleSets(force = true) }) {
                            Text(if (ruleSetState is SettingsViewModel.RuleSetState.Failed) "Повторить" else "Обновить")
                        }
                    }
                }
                Text(
                    text = "Пока правила не загружены, VPN работает в режиме «Обход локальной сети».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
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
            if (BuildConfig.ENABLE_SELF_UPDATE) {
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
            } else {
                Text(
                    "Обновления устанавливаются через Google Play. Самостоятельная установка APK отключена.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
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

    if (showDeviceMetadataConsentDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceMetadataConsentDialog = false },
            title = { Text("Разрешить передачу данных устройства?") },
            text = {
                Text(
                    "При обновлении подписок приложение будет передавать на адрес каждого включённого вами поставщика: " +
                        "уникальный псевдонимный HWID (хеш Android ID с отдельной солью приложения), модель устройства и версию Android. " +
                        "Сам исходный Android ID не отправляется. Эти данные получает сервер поставщика подписки и он определяет, как их использовать. " +
                        "Они могут применяться для ограничения числа устройств. Если оставить настройку выключенной, подписки продолжат загружаться без этих заголовков, " +
                        "но проверка лимита устройств у некоторых поставщиков может не работать."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDeviceMetadataConsent(true)
                    showDeviceMetadataConsentDialog = false
                }) { Text("Разрешить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceMetadataConsentDialog = false }) { Text("Не разрешать") }
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

private fun routingPresetTitle(preset: RoutingPreset): String = when (preset) {
    RoutingPreset.ALL_VPN -> "Всё через VPN"
    RoutingPreset.BYPASS_LAN -> "Обход локальной сети"
    RoutingPreset.BYPASS_RU -> "Обход локальной сети и сайтов РФ"
}

private fun routingPresetDescription(preset: RoutingPreset): String = when (preset) {
    RoutingPreset.ALL_VPN -> "Даже роутер и принтер доступны только через туннель"
    RoutingPreset.BYPASS_LAN -> "Рекомендуется: локальная сеть напрямую, остальное через VPN"
    RoutingPreset.BYPASS_RU -> "Российские сайты и адреса напрямую, остальное через VPN"
}
