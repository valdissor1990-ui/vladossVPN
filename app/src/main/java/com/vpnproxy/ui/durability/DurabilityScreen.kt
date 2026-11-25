package com.vpnproxy.ui.durability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.core.durability.BypassProfile
import com.vpnproxy.core.feature.FeatureFlags
import com.vpnproxy.ui.theme.HappInk
import com.vpnproxy.ui.theme.HappMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DurabilityScreen(
    onBack: () -> Unit,
    viewModel: DurabilityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Устойчивость", color = HappInk) },
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
                "Настройки меняют только способ установки соединения: сервер, DNS и маршруты остаются прежними. " +
                    "Изменения применятся после переподключения VPN.",
                color = HappMuted,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))

            BypassProfile.entries.forEach { profile ->
                ProfileCard(
                    profile = profile,
                    selected = state.profile == profile,
                    onClick = { viewModel.selectProfile(profile) }
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.justSaved) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Сохранено. Переподключите VPN, чтобы применить.",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }

            if (state.profile != BypassProfile.NORMAL) {
                TextButton(onClick = { viewModel.resetToNormal() }) {
                    Text("Сбросить в «Обычный»")
                }
            }

            if (FeatureFlags.BYPASS_AUTOPILOT_ENABLED) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Автопилот включён: при неудачном подключении приложение само переберёт до " +
                        "${com.vpnproxy.core.durability.BypassOrchestrator.MAX_ATTEMPTS} профилей и остановится.",
                    color = HappMuted,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Дробление TLS помогает против блокировки по имени сайта, но может замедлить соединение. " +
                    "Если сеть обычная, оставьте «Обычный».",
                color = HappMuted,
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileCard(
    profile: BypassProfile,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(
                    profile.title,
                    color = HappInk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(profile.summary, color = HappMuted, fontSize = 13.sp)
            }
        }
    }
}
