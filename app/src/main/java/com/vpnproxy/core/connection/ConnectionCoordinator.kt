            is ConnectionEvent.ServerSelected -> {
                if (event.traceId != null && event.traceId != activeTraceId) {
                    val isWaitingForServer = (current is ConnectionState.Connecting || current is ConnectionState.Reconnecting) && !hasServerSelected
                    if (isWaitingForServer) {
                        logW("Accepting ServerSelected with stale traceId to prevent timeout: eventTraceId=${event.traceId} activeTraceId=$activeTraceId")
                        activeTraceId = event.traceId
                    } else {
                        logW("Ignoring stale ServerSelected for ${event.server.config.name}: eventTraceId=${event.traceId} activeTraceId=$activeTraceId")
                        return
                    }
                }
                if (!isWithinProviderScope(event.server)) {
                    logW("ServerSelected rejected outside provider scope: ${event.server.config.name} providerTag=${event.server.config.providerTag} scope=${if (providerScopeBlocked) "BLOCKED" else allowedProviderTag ?: "ALL"}")
                    return
                }
                pendingServerName = event.server.config.name
                activeScoredServer = event.server
                hasServerSelected = true
                retryCount = 0
                val selectedKey = event.server.config.connectionKey()
                val selectedWasFallback = fallbackCandidates.any { it.config.connectionKey() == selectedKey }
                fallbackCandidates = fallbackCandidates.filterNot { it.config.connectionKey() == selectedKey }
                fallbackIndex = -1
                if (!selectedWasFallback) fallbackAttempts = 0
                logI("ServerSelected: ${event.server.config.name}")
                when (current) {
                    is ConnectionState.Disconnected, is ConnectionState.Error -> logD("ServerSelected while idle")
                    is ConnectionState.Connecting -> setState(ConnectionState.Reconnecting, reason = "ServerSelected while Connecting")
                    is ConnectionState.Connected, is ConnectionState.Reconnecting, is ConnectionState.Verifying -> {
                        if (!isFatalErrorLocked) setState(ConnectionState.Reconnecting, reason = "ServerSelected")
                    }
                    is ConnectionState.Disconnecting -> logD("ServerSelected ignored while Disconnecting")
                }
            }
            is ConnectionEvent.ReconnectRequested -> {
                if (isFatalErrorLocked) {
                    logI("ReconnectRequested ignored — fatalErrorLocked")
                    return
                }
                retryCount = 0
                when (current) {
                    is ConnectionState.Disconnected, is ConnectionState.Error -> {
                        if (hasServerSelected && !pendingServerName.isNullOrBlank()) {
                            setState(ConnectionState.Connecting, reason = "ReconnectRequested")
                        } else {
                            logD("ReconnectRequested ignored — no selected server")
                        }
                    }
                    is ConnectionState.Connecting,
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Verifying,
                    is ConnectionState.Connected -> {
                        setState(ConnectionState.Reconnecting, reason = "ReconnectRequested")
                    }
                    is ConnectionState.Disconnecting -> logD("ReconnectRequested ignored while Disconnecting")
                }
            }
            is ConnectionEvent.NetworkChanged -> {
                if (isFatalErrorLocked) {
                    logI("NetworkChanged ignored — fatalErrorLocked")
                    return
                }
                when (current) {
                    is ConnectionState.Connected -> if (hasServerSelected) setState(ConnectionState.Reconnecting, reason = "NetworkChanged")
                    is ConnectionState.Disconnected, is ConnectionState.Error -> {
                        if (hasServerSelected) {
                            retryCount = 0
                            setState(ConnectionState.Connecting, reason = "NetworkChanged")
                        } else logD("NetworkChanged ignored — no server selected")
                    }
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting, is ConnectionState.Verifying -> logD("NetworkChanged while $current — stay")
                    is ConnectionState.Disconnecting -> logD("NetworkChanged ignored while Disconnecting")
                }
            }
