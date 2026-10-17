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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.splittunnel.SplitTunnelMode
import com.vpnproxy.core.splittunnel.SplitTunnelRules
import com.vpnproxy.core.splittunnel.UserRulesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

@HiltViewModel
class SplitTunnelViewModel @Inject constructor(
    private val store: UserRulesStore
) : ViewModel() {
    val rules: StateFlow<SplitTunnelRules> = store.rules.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SplitTunnelRules()
    )

    fun setMode(mode: SplitTunnelMode) {
        viewModelScope.launch {
            store.updateRules { current -> current.copy(mode = mode) }
        }
    }

    fun setPackageSelected(packageName: String, selected: Boolean) {
        viewModelScope.launch {
            store.updateRules { current ->
                val packages = if (selected) current.selectedPackages + packageName
                else current.selectedPackages - packageName
                current.copy(selectedPackages = packages)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    onBack: () -> Unit,
    viewModel: SplitTunnelViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }

    LaunchedEffect(context) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    val filteredApps = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query.trim(), ignoreCase = true) ||
                it.packageName.contains(query.trim(), ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Обход VPN") },
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
            Text(
                "РЕЖИМ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                ModeRow(
                    title = "Все через VPN",
                    description = "Через VPN идут все приложения, кроме самого VPN-клиента",
                    selected = rules.mode == SplitTunnelMode.ALL_APPS,
                    onClick = { viewModel.setMode(SplitTunnelMode.ALL_APPS) }
                )
                ModeRow(
                    title = "Обходить выбранные",
                    description = "Выбранные приложения подключаются напрямую",
                    selected = rules.mode == SplitTunnelMode.EXCLUDE_SELECTED,
                    onClick = { viewModel.setMode(SplitTunnelMode.EXCLUDE_SELECTED) }
                )
                ModeRow(
                    title = "Только выбранные через VPN",
                    description = "Через VPN идут только отмеченные приложения",
                    selected = rules.mode == SplitTunnelMode.INCLUDE_SELECTED,
                    onClick = { viewModel.setMode(SplitTunnelMode.INCLUDE_SELECTED) }
                )
            }

            Divider(modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                label = { Text("Поиск приложений") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Text(
                "Приложения: ${filteredApps.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelf = app.packageName == context.packageName
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isSelf) {
                            viewModel.setPackageSelected(
                                app.packageName,
                                app.packageName !in rules.selectedPackages
                            )
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (isSelf) "${app.packageName} · всегда вне VPN"
                                else app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Checkbox(
                            checked = !isSelf && app.packageName in rules.selectedPackages,
                            onCheckedChange = { checked ->
                                if (!isSelf) viewModel.setPackageSelected(app.packageName, checked)
                            },
                            enabled = !isSelf
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val resolved: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
    return resolved.mapNotNull { info ->
        val activityInfo = info.activityInfo ?: return@mapNotNull null
        val packageName = activityInfo.packageName ?: return@mapNotNull null
        try {
            LaunchableApp(
                packageName = packageName,
                label = info.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
                    ?: packageName,
                icon = info.loadIcon(context.packageManager)
            )
        } catch (_: Exception) {
            null
        }
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}
