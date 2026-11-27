package com.vpnproxy.ui.privacy

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.core.privacy.PrivacyFact
import com.vpnproxy.core.privacy.PrivacyKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyReportScreen(
    onBack: () -> Unit,
    viewModel: PrivacyReportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Конфиденциальность") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(viewModel.exportText()))
                            Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                        },
                        enabled = state.report != null && !state.loading
                    ) {
                        Text("Копировать")
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                "Отчёт строится по текущим настройкам приложения и реальным источникам данных. Он не содержит URL подписок, адресов серверов, ключей или токенов.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (state.loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refresh) { Text("Повторить") }
            }
            state.report?.let { report ->
                report.warnings.forEach { warning ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            warning,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                PrivacyKind.entries.forEach { kind ->
                    val facts = report.facts.filter { it.kind == kind }
                    if (facts.isNotEmpty()) {
                        Text(
                            kind.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                        )
                        facts.forEach { fact ->
                            PrivacyFactCard(fact)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                TextButton(
                    onClick = viewModel::refresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.loading
                ) {
                    Text("Обновить отчёт")
                }
            }
        }
    }
}

@Composable
private fun PrivacyFactCard(fact: PrivacyFact) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(fact.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(fact.detail, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (fact.dataLeavesDevice) "Передача с устройства" else "Остаётся на устройстве",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fact.dataLeavesDevice) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (fact.requiresOptIn) {
                    Text(
                        " · только с согласия",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Источник: ${fact.source.title}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}
