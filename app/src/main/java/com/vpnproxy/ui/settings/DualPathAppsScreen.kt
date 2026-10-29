package com.vpnproxy.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.dualpath.AiAppCatalog
import com.vpnproxy.core.dualpath.DualPathSettings
import com.vpnproxy.core.dualpath.DualPathStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private data class DualPathAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val suggested: Boolean
)

@HiltViewModel
class DualPathAppsViewModel @Inject constructor(
    private val store: DualPathStore
) : ViewModel() {
    val settings: StateFlow<DualPathSettings> = store.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DualPathSettings()
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(enabled) }
    }

    fun setPackageSelected(packageName: String, selected: Boolean) {
        viewModelScope.launch { store.setPackageSelected(packageName, selected) }
    }

    fun clearAll() {
        viewModelScope.launch { store.setPackages(emptySet()) }
    }

    fun selectSuggestedInstalled(installedSuggested: Set<String>) {
        viewModelScope.launch {
            val current = store.read().packages
            store.setPackages(current + installedSuggested)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPathAppsScreen(
    onBack: () -> Unit,
    viewModel: DualPathAppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<DualPathAppItem>>(emptyList()) }

    LaunchedEffect(context) {
        apps = withContext(Dispatchers.IO) { loadDualPathApps(context) }
    }

    val filteredApps = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query.trim(), ignoreCase = true) ||
                it.packageName.contains(query.trim(), ignoreCase = true)
        }
    }

    val installedSuggested = remember(apps) {
        apps.filter { it.suggested }.map { it.packageName }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Зарубежный обход") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Включить зарубежный маршрут", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Отмеченные приложения идут через не-RU сервер; остальные — как обычно",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.selectSuggestedInstalled(installedSuggested) },
                    enabled = settings.enabled && installedSuggested.isNotEmpty()
                ) {
                    Text("Отметить найденные ИИ")
                }
                TextButton(
                    onClick = { viewModel.clearAll() },
                    enabled = settings.enabled && settings.packages.isNotEmpty()
                ) {
                    Text("Снять все")
                }
            }

            Text(
                "Выбрано: ${settings.packages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                label = { Text("Поиск приложений") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelf = app.packageName == context.packageName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = settings.enabled && !isSelf) {
                                viewModel.setPackageSelected(
                                    app.packageName,
                                    app.packageName !in settings.packages
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = remember(app.packageName) {
                                app.icon.toBitmap(96, 96).asImageBitmap()
                            },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                if (app.suggested) "${app.label} · ИИ"
                                else app.label,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (isSelf) "${app.packageName} · VPN-клиент"
                                else app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Checkbox(
                            checked = !isSelf && app.packageName in settings.packages,
                            onCheckedChange = { checked ->
                                if (!isSelf) viewModel.setPackageSelected(app.packageName, checked)
                            },
                            enabled = settings.enabled && !isSelf
                        )
                    }
                }
            }
        }
    }
}

private fun loadDualPathApps(context: Context): List<DualPathAppItem> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
    val suggested = AiAppCatalog.SUGGESTED_PACKAGES
    return resolved.mapNotNull { info ->
        val activityInfo = info.activityInfo ?: return@mapNotNull null
        val packageName = activityInfo.packageName ?: return@mapNotNull null
        try {
            DualPathAppItem(
                packageName = packageName,
                label = info.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
                    ?: packageName,
                icon = info.loadIcon(context.packageManager),
                suggested = packageName in suggested
            )
        } catch (_: Exception) {
            null
        }
    }.distinctBy { it.packageName }
        .sortedWith(
            compareByDescending<DualPathAppItem> { it.suggested }
                .thenBy { it.label.lowercase() }
        )
}
