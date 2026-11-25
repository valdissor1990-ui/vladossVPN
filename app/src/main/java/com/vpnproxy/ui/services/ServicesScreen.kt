package com.vpnproxy.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.core.services.ServiceCatalog
import com.vpnproxy.core.services.ServiceCheckResult
import com.vpnproxy.core.services.ServiceCheckStatus
import com.vpnproxy.core.services.ServiceTarget
import com.vpnproxy.ui.theme.HappInk
import com.vpnproxy.ui.theme.HappMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    onBack: () -> Unit,
    viewModel: ServicesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Проверка сервисов", color = HappInk) },
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
                "Контрольные запросы идут через активный VPN. Результат показывает доступность контрольного адреса, а не полную работу сервиса.",
                color = HappMuted,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(12.dp))

            if (!state.vpnActive && state.lastCheckedAt == null && !state.isChecking) {
                NoticeCard("VPN не подключён. Подключите VPN и повторите проверку.")
                Spacer(Modifier.height(12.dp))
            }

            if (state.isChecking && state.coreTransitioning) {
                NoticeCard("VPN сейчас переподключается. Проверка может быть неточной.")
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = { viewModel.checkAll() },
                enabled = !state.isChecking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Проверяю...")
                } else {
                    Text(if (state.lastCheckedAt == null) "Проверить всё" else "Проверить снова")
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))

            ServiceCatalog.defaultTargets.forEach { target ->
                ServiceRow(
                    target = target,
                    result = state.results[target.id],
                    checking = state.isChecking
                )
                Spacer(Modifier.height(8.dp))
            }

            state.lastCheckedAt?.let { checkedAt ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Проверено: ${formatTime(checkedAt)}",
                    color = HappMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ServiceRow(
    target: ServiceTarget,
    result: ServiceCheckResult?,
    checking: Boolean
) {
    val status = result?.status
    val label: String
    val color: Color
    when {
        checking && result == null -> {
            label = "Проверка..."
            color = HappMuted
        }
        status == ServiceCheckStatus.REACHABLE -> {
            label = "Контрольный запрос прошёл"
            color = Color(0xFF2E7D32)
        }
        status == ServiceCheckStatus.UNREACHABLE -> {
            label = "Контрольный запрос не прошёл"
            color = Color(0xFFC62828)
        }
        status == ServiceCheckStatus.UNKNOWN -> {
            label = "Не удалось проверить"
            color = Color(0xFFF9A825)
        }
        status == ServiceCheckStatus.VPN_OFF -> {
            label = "VPN выключен"
            color = HappMuted
        }
        status == ServiceCheckStatus.TRANSITION -> {
            label = "VPN переподключается"
            color = HappMuted
        }
        else -> {
            label = "Не проверено"
            color = HappMuted
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    target.title.take(1),
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(target.title, color = HappInk, fontWeight = FontWeight.Medium)
                Text(label, color = color, fontSize = 12.sp)
            }
            result?.latencyMs?.let { latency ->
                Text("$latency мс", color = HappMuted, fontSize = 12.sp)
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

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
