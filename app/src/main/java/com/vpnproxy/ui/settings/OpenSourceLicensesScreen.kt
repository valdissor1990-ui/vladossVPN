package com.vpnproxy.ui.settings

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private val bundledLicenseFiles = listOf(
    "GPL-3.0-or-later",
    "Apache-2.0",
    "BSD-3-Clause",
    "EPL-2.0",
    "GPL-2.0-only",
    "Classpath-exception-2.0"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showInventory by remember { mutableStateOf(false) }
    var showGoInventory by remember { mutableStateOf(false) }
    var selectedLicense by remember { mutableStateOf("GPL-3.0-or-later") }
    var selectedLibboxLicense by remember { mutableStateOf<String?>(null) }

    val coreNotice = remember {
        context.assets.open("licenses/sing-box-lx-NOTICE.txt")
            .bufferedReader()
            .use { it.readText() }
    }
    val hasGoInventory = remember {
        context.assets.list("licenses")?.contains("libbox-go-licenses.csv") == true
    }
    val libboxLicenseFiles = remember {
        listAssetFiles(context, "licenses/libbox")
    }
    val selectedLibboxLicenseText = remember(selectedLibboxLicense) {
        selectedLibboxLicense?.let { path ->
            context.assets.open(path).bufferedReader().use { it.readText() }
        }
    }
    val goInventoryText = remember(hasGoInventory) {
        if (hasGoInventory) {
            context.assets.open("licenses/libbox-go-licenses.csv")
                .bufferedReader()
                .use { it.readText() }
        } else ""
    }
    val selectedLicenseText = remember(selectedLicense) {
        context.assets.open("licenses/$selectedLicense.txt")
            .bufferedReader()
            .use { it.readText() }
    }

    if (showGoInventory) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Нативные зависимости libbox") },
                    navigationIcon = {
                        IconButton(onClick = { showGoInventory = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад к лицензиям")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text("Модули, версия и тип лицензии", style = MaterialTheme.typography.titleMedium)
                Text(goInventoryText, style = MaterialTheme.typography.bodySmall)
                Text("Лицензии и уведомления нативных зависимостей", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                libboxLicenseFiles.forEach { file ->
                    TextButton(onClick = { selectedLibboxLicense = file }) {
                        Text(file.substringAfterLast("/"))
                    }
                }
                selectedLibboxLicenseText?.let { text ->
                    Text(selectedLibboxLicense ?: "", style = MaterialTheme.typography.titleSmall)
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                }
            }
        }
        return
    }

    if (showInventory) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Сторонние компоненты") },
                    navigationIcon = {
                        IconButton(onClick = { showInventory = false }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад к лицензиям")
                        }
                    }
                )
            }
        ) { padding ->
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = false
                        webViewClient = WebViewClient()
                        loadUrl("file:///android_asset/index.html")
                    }
                }
            )
        }
        return
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
                "Проект VLADOSS VPN заявлен под GPL-3.0-or-later и включает ядро sing-box-lx/libbox под GPL-3.0-or-later. " +
                    "Программное обеспечение предоставляется без гарантий в пределах, допускаемых применимым правом. " +
                    "Исходный код проекта: https://github.com/valdissor1990-ui/vladossVPN",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
            Text("Уведомления компонента", style = MaterialTheme.typography.titleMedium)
            Text(
                coreNotice,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            Text("Список сторонних Gradle-компонентов", style = MaterialTheme.typography.titleMedium)
            Text(
                "Отчёт сформирован по release-зависимостям приложения. Он содержит названия, версии, лицензии и ссылки на проекты. " +
                    "Составные нативные зависимости внутри libbox.aar требуют отдельной проверки по исходникам sing-box-lx.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
            TextButton(onClick = { showInventory = true }) {
                Text("Открыть полный отчёт")
            }
            if (hasGoInventory) {
                TextButton(onClick = { showGoInventory = true }) {
                    Text("Открыть отчёт нативного ядра")
                }
            }

            Text("Тексты лицензий", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                bundledLicenseFiles.forEach { licenseName ->
                    TextButton(onClick = { selectedLicense = licenseName }) {
                        Text(if (selectedLicense == licenseName) "• $licenseName" else licenseName)
                    }
                }
            }
            Text(selectedLicense, style = MaterialTheme.typography.titleSmall)
            Text(
                selectedLicenseText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
        }
    }
}


private fun listAssetFiles(context: Context, path: String): List<String> {
    val children = context.assets.list(path)?.toList().orEmpty()
    return children.flatMap { child ->
        val childPath = "$path/$child"
        val nested = context.assets.list(childPath)?.toList().orEmpty()
        if (nested.isEmpty()) listOf(childPath) else listAssetFiles(context, childPath)
    }
}
