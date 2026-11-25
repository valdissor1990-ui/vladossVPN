package com.vpnproxy.ui.doctor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.core.doctor.DiagnosticResult
import com.vpnproxy.core.doctor.DiagnosticStatus
import com.vpnproxy.core.doctor.DoctorReport
import com.vpnproxy.core.doctor.RemedySafety
import com.vpnproxy.core.doctor.label
import com.vpnproxy.ui.theme.HappInk
import com.vpnproxy.ui.theme.HappMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorScreen(
    onBack: () -> Unit,
    viewModel: DoctorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var consent by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Доктор подключения", color = HappInk) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = HappInk)
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
                "Пошаговая проверка подключения: подписка, профиль, DNS, TCP, TLS, прокси, TUN, трафик и контрольные сервисы. Адреса серверов, ключи и содержимое конфигураций не попадают в журнал.",
                color = HappMuted,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))

            if (state.vpnActive) {
                NoticeCard(
                    "VPN включён: выполняются шаги TUN, трафика и сервисов. Проверки TLS и прокси-пробы требуют остановки VPN — они получат статус «Пропущено». Чтобы проверить их, остановите VPN и запустите Доктора снова с согласием на изолированную пробу."
                )
                Spacer(Modifier.height(8.dp))
            }
            if (!state.vpnActive) {
                NoticeCard(
                    "VPN выключен: доступны проверки подписки, профиля, TLS и прокси-пробы. Шаги DNS, TCP, TUN, трафика и сервисов требуют включённого VPN."
                )
                Spacer(Modifier.height(8.dp))
            }
            if (state.coreTransitioning) {
                NoticeCard("VPN сейчас переподключается. Часть проверок может быть пропущена.")
                Spacer(Modifier.height(8.dp))
            }

            ConsentRow(
                checked = consent,
                enabled = !state.isRunning,
                onCheckedChange = { consent = it }
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.run(consent) },
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Проверяю...")
                } else {
                    Text(if (state.report == null) "Проверить подключение" else "Проверить снова")
                }
            }

            if (state.isRunning) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отменить")
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))

            state.results.forEach { result ->
                DoctorResultCard(result)
                Spacer(Modifier.height(8.dp))
            }

            state.report?.let { report ->
                Spacer(Modifier.height(8.dp))
                ReportSummary(
                    report = report,
                    onCopy = { copyReport(context, report.exportText()) }
                )
            }
        }
    }
}

@Composable
private fun ConsentRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Разрешить временный запуск отдельного ядра",
                color = HappInk,
                fontSize = 13.sp
            )
            Text(
                "Нужно для прокси-пробы и проверки TLS. VPN при этом должен быть выключен.",
                color = HappMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DoctorResultCard(result: DiagnosticResult) {
    val color = statusColor(result.status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    result.step.title.take(1),
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.step.title,
                        color = HappInk,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(result.status.label(), color = color, fontSize = 12.sp)
                }
                Text(result.summary, color = HappMuted, fontSize = 12.sp)
                result.remedy?.let { remedy ->
                    val suffix = if (remedy.safety == RemedySafety.REQUIRES_CONFIRM) {
                        " (нужно подтверждение)"
                    } else {
                        ""
                    }
                    Text(
                        "Действие: ${remedy.title}$suffix",
                        color = HappInk,
                        fontSize = 12.sp
                    )
                }
            }
            Text("${result.durationMs} мс", color = HappMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ReportSummary(report: DoctorReport, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(report.headline(), color = HappInk, fontWeight = FontWeight.Medium)
            Text(
                "Шагов: ${report.results.size}, время: ${report.durationMs} мс",
                color = HappMuted,
                fontSize = 12.sp
            )
            TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                Text("Скопировать отчёт")
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            color = HappInk,
            fontSize = 13.sp
        )
    }
}

private fun statusColor(status: DiagnosticStatus): Color = when (status) {
    DiagnosticStatus.OK -> Color(0xFF2E7D32)
    DiagnosticStatus.WARN -> Color(0xFFF9A825)
    DiagnosticStatus.FAIL -> Color(0xFFC62828)
    DiagnosticStatus.SKIP -> HappMuted
}

private fun copyReport(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "Буфер обмена недоступен", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("doctor-report", text))
    Toast.makeText(context, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
}
