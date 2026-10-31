package com.vpnproxy.ace.ipc;

import com.vpnproxy.ace.ipc.IAceTelemetryReceiver;

/**
 * Stage B Binder interface: :ace process → main process event sink.
 * Signature-protected. No VPN control commands.
 */
interface IAceEventReceiver {
    void onTestEvent(String payload);
    void onError(String message);
    void registerTelemetryReceiver(IAceTelemetryReceiver receiver);
    void unregisterTelemetryReceiver(IAceTelemetryReceiver receiver);
}
