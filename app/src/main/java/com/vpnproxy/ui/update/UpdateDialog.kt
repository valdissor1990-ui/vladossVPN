package com.vpnproxy.ui.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vpnproxy.update.UpdateManager

/**
 * Диалог обновления. Показывает версию, changelog, прогресс скачивания.
 */
@Composable
fun UpdateDialog(
    state: UpdateManager.State,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is UpdateManager.State.UpdateAvailable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Доступно обновление ${state.release.extractVersionName()}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = state.release.body.ifBlank { "Список изменений недоступен" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDownload) { Text("Скачать и установить") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Позже") }
            }
        )

        is UpdateManager.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Скачивание…") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${state.percent}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )

        is UpdateManager.State.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Готово к установке") },
            text = { Text("APK ${state.release.extractVersionName()} скачан. Нажмите «Установить», затем подтвердите установку в системном окне.") },
            confirmButton = {
                Button(onClick = onInstall) { Text("Установить") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        )

        is UpdateManager.State.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Ошибка обновления") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        )

        else -> Unit
    }
}
