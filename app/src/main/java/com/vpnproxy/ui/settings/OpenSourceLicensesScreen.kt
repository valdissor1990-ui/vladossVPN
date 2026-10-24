package com.vpnproxy.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gplText = remember {
        context.assets.open("licenses/GPL-3.0-or-later.txt")
            .bufferedReader()
            .use { it.readText() }
    }
    val coreNotice = remember {
        context.assets.open("licenses/sing-box-lx-NOTICE.txt")
            .bufferedReader()
            .use { it.readText() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Открытые лицензии") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
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
            Text("Лицензия VPN-ядра", style = MaterialTheme.typography.titleLarge)
            Text(
                "Приложение включает sing-box-lx/libbox под GPL-3.0-or-later. " +
                    "Программное обеспечение предоставляется без гарантий в пределах, допускаемых применимым правом. " +
                    "Исходный код проекта: https://github.com/valdissor1990-ui/vladossVPN",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            Text("Уведомления компонента", style = MaterialTheme.typography.titleMedium)
            Text(
                coreNotice,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
            Text("GNU General Public License, версия 3", style = MaterialTheme.typography.titleMedium)
            Text(
                gplText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
        }
    }
}
