    fun onVpnPermissionGranted() {
        val traceId = activeTraceId ?: TraceId.newId().also { activeTraceId = it }
        _uiState.value = _uiState.value.copy(connectionState = ConnectionUiState.CONNECTING, isBusy = true)
        armConnectingWatchdog()
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val selectionStartedAt = android.os.SystemClock.elapsedRealtime()
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val hasNetwork = cm.activeNetwork != null
                var configs = subscriptionRepository.getAllConfigs()
                if (activeTraceId != traceId) return@launch
                var refreshedSubscription = false
                if (hasNetwork) {
                    configs = try {
                        subscriptionRepository.refreshAll()
                        subscriptionRepository.getAllConfigs().also { refreshed ->
                            serverScanner.scanAfterSubscriptionUpdate(refreshed)
                            refreshedSubscription = true
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        logCollector.warn(TAG, tmsg("Subscription refresh failed: ${e.message}"))
                        emptyList()
                    }
                }
                if (activeTraceId != traceId) return@launch
                if (configs.isEmpty()) configs = subscriptionRepository.getCachedConfigs()
                configs = filterConfigsForSelectedSubscription(configs)
                if (configs.isEmpty()) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                val selected = withTimeoutOrNull(SERVER_SELECTION_TIMEOUT_MS) {
                    val allowedKeys = configs.map { it.connectionKey() }.toSet()
                    val scanned = serverScanner.availableServers()
                        .filter { it.config.connectionKey() in allowedKeys }
                        .filterNot { autoSwitcher.isBlacklisted(it.config) }
                    if (_uiState.value.isAutomaticMode) {
                        val bestScanned = scanned.minByOrNull { it.latencyMs }
                        if (bestScanned != null) autoSwitcher.selectScanned(bestScanned, scanned)
                        else {
                            val currentConfig = autoSwitcher.currentServer.value?.config
                                ?.takeIf { current -> configs.any { it.connectionKey() == current.connectionKey() } }
                                ?: configs.first()
                            autoSwitcher.selectImmediate(currentConfig, configs)
                        }
                    } else {
                        manuallySelectedServer?.takeIf { s -> configs.any { it.connectionKey() == s.config.connectionKey() } }
                            ?: run {
                                val bestScanned = scanned.minByOrNull { it.latencyMs }
                                if (bestScanned != null) autoSwitcher.selectScanned(bestScanned, scanned)
                                else autoSwitcher.selectImmediate(configs.first(), configs)
                            }
                    }
                }
                if (selected == null) {
                    connectionCoordinator.send(ConnectionEvent.ShowError("Нет доступных серверов"))
                    return@launch
                }
                if (activeTraceId != traceId) return@launch
                connectionCoordinator.send(ConnectionEvent.ServerSelected(selected, traceId))
                pendingRankingConfigs = configs
                ensureServiceRunning()
                logCollector.info(TAG, tmsg("Selected ${selected.config.name} in ${android.os.SystemClock.elapsedRealtime() - selectionStartedAt}ms refreshed=$refreshedSubscription"))
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                connectionCoordinator.send(ConnectionEvent.ShowError(e.message ?: "Ошибка выбора сервера"))
            }
        }
    }
