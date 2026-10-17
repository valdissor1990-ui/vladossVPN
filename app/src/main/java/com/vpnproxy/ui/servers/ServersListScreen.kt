package com.vpnproxy.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.smartconnect.ServerMetrics
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ServersListViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    private val autoSwitcher: AutoSwitcher
) : ViewModel() {

    private val _servers = MutableStateFlow<List<ScoredServer>>(emptyList())
    val servers: StateFlow<List<ScoredServer>> = _servers.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allConfigs.collect { configs ->
                if (configs.isNotEmpty() && autoSwitcher.rankedServers.value.isEmpty()) {
                    autoSwitcher.selectBest(configs)
                }
                _servers.value = autoSwitcher.rankedServers.value
            }
        }
        viewModelScope.launch {
            autoSwitcher.rankedServers.collect { _servers.value = it }
        }
        viewModelScope.launch { repository.refreshAll() }
    }
}

/**
 * Список серверов без Scaffold — для ModalBottomSheet на HomeScreen.
 * 2-колоночный grid (Фича 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListContent(
    onServerSelected: (ScoredServer) -> Unit = {},
    viewModel: ServersListViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val filtered = remember(servers, query) {
        if (query.isBlank()) servers
        else servers.filter {
            it.config.name.contains(query, ignoreCase = true) ||
                it.config.address.contains(query, ignoreCase = true) ||
                it.config.providerTag.contains(query, ignoreCase = true)
        }
    }

    val uniqueItems = remember(filtered) {
        filtered.distinctBy {
            "${it.config.address}|${it.config.port}|${it.config.uuid}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Серверы (${servers.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Поиск по имени/хосту/провайдеру") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true
        )

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (servers.isEmpty()) {
                        "Список пуст. Обновите подписки."
                    } else {
                        "Ничего не найдено"
                    },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 480.dp)
            ) {
                items(
                    items = uniqueItems,
                    key = {
                        ServerMetrics.serverKey(it.config.address, it.config.port, it.config.uuid)
                    }
                ) { scoredServer ->
                    ServerGridCard(
                        server = scoredServer,
                        onClick = { onServerSelected(scoredServer) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersListScreen(
    onBack: () -> Unit,
    viewModel: ServersListViewModel = hiltViewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ServerListContent(viewModel = viewModel)
        }
    }
}
