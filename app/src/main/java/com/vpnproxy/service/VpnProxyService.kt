        when (intent?.action) {
            ACTION_FORCE_STOP -> {
                Log.e("VpnService", "FORCE_STOP — RequestDisconnect via actor then teardown")
                connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
                forceStopService("notification FORCE_STOP")
            }
            ACTION_REQUEST_DISCONNECT -> {
                Log.e("VpnService", "REQUEST_DISCONNECT → ConnectionEvent.RequestDisconnect")
                connectionCoordinator.send(ConnectionEvent.RequestDisconnect)
            }
            ACTION_REQUEST_RECONNECT -> {
                Log.d(TAG, "REQUEST_RECONNECT → ConnectionEvent.ReconnectRequested")
                connectionCoordinator.send(ConnectionEvent.ReconnectRequested)
            }
            ACTION_ENSURE_RUNNING, null -> {
                Log.d(TAG, "ENSURE_RUNNING — actor state=${connectionCoordinator.state.value}")
            }
            else -> Log.d(TAG, "Unhandled action ${intent?.action}")
        }
