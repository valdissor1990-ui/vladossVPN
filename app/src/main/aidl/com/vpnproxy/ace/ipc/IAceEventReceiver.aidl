package com.vpnproxy.ace.ipc;

/**
 * Stage B Binder interface: :ace process → main process event sink.
 * Signature-protected. No VPN control commands.
 */
interface IAceEventReceiver {
    void onTestEvent(String payload);
    void onError(String message);
}
